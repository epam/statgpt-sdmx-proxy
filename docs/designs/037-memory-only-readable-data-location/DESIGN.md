# Design 037: Memory-only ReadableDataLocation factory

**Status:** proposed
**Issue:** [#105 — Availability requests hangs](https://github.com/epam/statgpt-sdmx-proxy/issues/105)

## Problem

On a pod that has served traffic for a while, availability (and in principle any
converted) requests suddenly start logging:

```
i.s.u.c.i.SdmxSourceReadableDataLocationFactory: Limit hit on getting ReadableDataLocation, overflow to tmp URI
```

and then hang indefinitely. Restarting the pod fixes it — until it drifts back.

### Root cause (verified against sdmx-core source)

The proxy's three conversion services parse upstream responses by first wrapping
the registry's response `InputStream` in a re-readable `ReadableDataLocation`,
obtained from a **single shared singleton** of sdmx-core's
`SdmxSourceReadableDataLocationFactory`
(`sdmx-core-2.3.9/fusion-utils/src/main/java/io/sdmx/utils/core/io/SdmxSourceReadableDataLocationFactory.java`):

- `StreamingAvailabilityConversionService.java:124`
- `StreamingStructureConversionService.java:91`
- `StreamingDataConversionService.java:141`

The factory bean is defined once in
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/config/SdmxSourceConfig.java:35`
and injected into all three services, so its mutable state is shared across every
request thread.

That factory keeps an in-memory budget: `maxMemory` (default 30 MB) and a running
`memoryUseage` counter (instance fields). Every in-memory read does
`memoryUseage += bytes` (`SdmxSourceReadableDataLocationFactory.java:171`). The
**only** place `memoryUseage` is decremented is
`OverflowReadableDataLocation.closeInternal()` (`:51-53`), which runs **only when
someone calls `close()` on the returned location**.

**None of the three conversion services ever close the location.** They create
it, hand it to the reader, and drop the reference. Therefore `memoryUseage` only
ever grows. Once cumulative traffic pushes it permanently past 30 MB, the guard
`if (memoryUseage < maxMemory)` (`:164`) is always false, so **every** subsequent
request — regardless of size — skips the in-memory path and goes straight to the
"overflow to tmp URI" disk path (`:187-210`). The counter never comes back down,
which is why a restart (resetting the field to 0) temporarily fixes it. The
unsynchronized `memoryUseage += i` across concurrent threads only accelerates the
drift.

**Why the disk path then hangs:** the overflow path writes through
`URIUtil` (`fusion-utils/.../io/URIUtil.java`), which tracks open streams in
**static, unsynchronized `HashMap`s** (`outputstreamMap`/`inputstreamMap`,
`URIUtil.java:37-38`) mutated by every thread concurrently — corruption/spin
territory — and performs real file I/O into `java.io.tmpdir`
(`FileUtil.createTemporaryFile`, `FileUtil.java:37-52`), which in our container is
a constrained/slow overlay. So the leak *forces* the fragile disk path, and the
disk path stalls.

### Why sdmx-core buffers at all

The SDMX readers require a `ReadableDataLocation`, which by contract is
*re-readable* — `getInputStream()` "is guaranteed to return a new InputStream on
each method call" (`fusion-api/.../io/ReadableDataLocation.java:32-37`). Readers
rely on this to sniff the format (`AbstractReadableDataLocation.getFormat()` →
`FormatUtil.determineFileFormat(this)` reads the stream) and then parse. A raw
HTTP response stream is single-pass, so it must be materialized first. The 30 MB
cap + disk spill is sdmx-core's way of bounding heap while still supporting
arbitrarily large payloads. We do not need the disk-spill behaviour: we have no
reliable writable disk in-container, and it is the source of the hang.

## Decision

Replace the use of `SdmxSourceReadableDataLocationFactory` with our own
**memory-only** factory that reads the upstream stream fully into a `byte[]` and
returns a `byte[]`-backed `ReadableDataLocation`. No shared counter, no disk, no
leak, no hang. To avoid trading the leak-induced outage for an OOM-induced one,
the read is bounded by a **configurable cap that fails fast** with a clear error
instead of exhausting the heap.

Decisions confirmed with the requester:
- **Cap:** configurable, fail-fast (default 256 MiB). Not unbounded.
- **Scope:** all three conversion services switch. The leaky sdmx-core factory
  is removed from the application entirely.

## Approach

### New: `InMemoryReadableDataLocation`

A minimal `byte[]`-backed `ReadableDataLocation`, in the existing sdmx-core
override package `services/sdmxsource/`. It extends sdmx-core's
`AbstractReadableDataLocation`
(`fusion-utils/.../io/AbstractReadableDataLocation.java`) so it inherits the
`isClosed`/`isProtected`/`getSize`/`getInputStream`/`split`/`getFormat`
plumbing the readers expect — in particular `getFormat()`'s format-sniffing,
which re-reads the stream. Each `getInputStream()` returns a fresh
`ByteArrayInputStream` over the same array, satisfying the re-readable contract.

```java
package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.utils.core.io.AbstractReadableDataLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * A {@link ReadableDataLocation} backed entirely by an in-memory byte array.
 * <p>
 * Replaces sdmx-core's {@code SdmxSourceReadableDataLocationFactory}, whose shared
 * memory-budget counter leaked (never decremented because we never closed the
 * locations) and forced every request onto a fragile temp-file path that hangs in
 * our container. See design 037 / issue #105.
 * <p>
 * Each {@link #getInputStream()} returns a fresh stream over the same array, so the
 * re-readable contract (format sniffing, then parse) holds. Holding the whole
 * payload in heap is acceptable because the upstream read is size-capped upstream
 * by {@link InMemoryReadableDataLocationFactory}.
 */
public class InMemoryReadableDataLocation extends AbstractReadableDataLocation {

    private static final long serialVersionUID = 1L;

    private final byte[] bytes;
    private final String name;

    public InMemoryReadableDataLocation(byte[] bytes) {
        this(bytes, null);
    }

    public InMemoryReadableDataLocation(byte[] bytes, String name) {
        this.bytes = bytes;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    protected InputStream getStreamInternal() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    protected void closeInternal() {
        // Nothing to release: the byte[] is GC-managed and no OS handles are held.
        // Re-reads after close are already blocked by AbstractReadableDataLocation.
    }

    @Override
    protected long calculateSize() {
        return bytes.length;
    }

    @Override
    public ReadableDataLocation copy() {
        // The array is never mutated, so sharing it across copies is safe.
        return new InMemoryReadableDataLocation(bytes, name);
    }
}
```

Note: `AbstractReadableDataLocation`'s abstract members are `protected
getStreamInternal()`, `protected closeInternal()`, `protected calculateSize()`;
`getName()` and `copy()` come from the `ReadableDataLocation` interface and are
public. The signatures above match.

### New: `InMemoryReadableDataLocationFactory`

A small Spring-managed service exposing the single overload the three conversion
services actually call — `getReadableDataLocation(InputStream)`. It reads the
stream fully into a `byte[]`, aborting if the cap is exceeded, and always closes
the source stream (the Feign response stream) when done.

```java
package com.epam.sdmxproxy.services.sdmxsource;

import com.epam.sdmxproxy.exception.DataConversionException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import io.sdmx.api.io.ReadableDataLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Memory-only replacement for sdmx-core's leaky, disk-spilling
 * {@code SdmxSourceReadableDataLocationFactory}. See design 037 / issue #105.
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryReadableDataLocationFactory {

    private static final int CHUNK_SIZE = 8192;

    private final long maxInMemoryBytes;

    public ReadableDataLocation getReadableDataLocation(InputStream inputStream) {
        return new InMemoryReadableDataLocation(readFully(inputStream));
    }

    private byte[] readFully(InputStream inputStream) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_SIZE];
        long total = 0;
        try (InputStream in = inputStream) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > maxInMemoryBytes) {
                    throw new ResponseTooLargeException("Upstream response exceeded the in-memory processing limit of " + maxInMemoryBytes + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new DataConversionException("Failed to read upstream response into memory", e);
        }
        return buffer.toByteArray();
    }
}
```

Heap note: `ByteArrayOutputStream` grows by doubling and `toByteArray()` copies,
so transient peak is ~2-3x the payload. With a 256 MiB cap, a single
maximal request peaks at roughly 0.5-0.75 GiB transiently before the copy is
handed off and the buffer is GC'd. Sizing of the cap and pod heap is an operator
concern; the default is documented in `application.yaml`.

### New: `ResponseTooLargeException`

The payload comes *from the registry*, so this is a server-side resource limit.
There is no 413/502 family in the handler; the existing pattern is to subclass a
family. We use `ServerErrorException` (HTTP 500) and override `getClientMessage()`
to surface a curated, safe, actionable phrase — explicitly sanctioned by
`ServerErrorException`'s Javadoc ("Subclasses MAY override `getClientMessage()`
when the message is curated and safe"). No change to `GlobalExceptionHandler` is
needed; it already dispatches the whole `ServerErrorException` family to 500.

```java
package com.epam.sdmxproxy.exception;

/**
 * Thrown when an upstream registry response exceeds the proxy's configured
 * in-memory processing limit. Maps to HTTP 500 via the ServerErrorException family.
 */
public class ResponseTooLargeException extends ServerErrorException {

    public ResponseTooLargeException(String message) {
        super(message);
    }

    public ResponseTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return "The upstream response was too large for the proxy to process. Narrow your query (e.g. add filters or a smaller time range) and try again.";
    }
}
```

### Config: the cap

Wired via `@Value` on the bean factory method (single scalar, no new properties
class needed). Default 256 MiB (`268435456`), overridable by env var.

In `SdmxSourceConfig.java`, replace the old bean:

```java
// REMOVE:
@Bean
public SdmxSourceReadableDataLocationFactory sdmxSourceReadableDataLocationFactory() {
    return new SdmxSourceReadableDataLocationFactory();
}

// ADD:
@Bean
public InMemoryReadableDataLocationFactory inMemoryReadableDataLocationFactory(
        @Value("${sdmxproxy.conversion.max-in-memory-bytes:268435456}") long maxInMemoryBytes) {
    return new InMemoryReadableDataLocationFactory(maxInMemoryBytes);
}
```

(`import org.springframework.beans.factory.annotation.Value;` and the two new
class imports; drop the `io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory`
import.)

In `application.yaml`, under the `sdmxproxy:` root:

```yaml
  conversion:
    # Maximum bytes an upstream registry response may occupy in memory during
    # format conversion. Exceeding this aborts the request with HTTP 500 rather
    # than risking an out-of-memory crash of the pod. Transient peak heap during
    # the read is ~2-3x this value.
    max-in-memory-bytes: ${SDMX_MAX_IN_MEMORY_BYTES:268435456}
```

### Service changes (all three)

In each of the three services, change the injected field's **type** (and import)
from `SdmxSourceReadableDataLocationFactory` to
`InMemoryReadableDataLocationFactory`. The call sites
(`...getReadableDataLocation(inputStream)` / `(effectiveInputStream)`) are
unchanged — the method name and signature are identical.

| Service | Field (line) |
|---|---|
| `StreamingAvailabilityConversionService` | `sdmxSourceReadableDataLocationFactory` (`:47`) |
| `StreamingStructureConversionService` | `sdmxSourceReadableDataLocationFactory` (`:42`) |
| `StreamingDataConversionService` | `readableDataLocationFactory` (`:54`) |

Field names may be kept as-is to minimise churn, or renamed for clarity; either
is acceptable. Imports change from
`io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory` to
`com.epam.sdmxproxy.services.sdmxsource.InMemoryReadableDataLocationFactory`.

## Files Affected

| File | Change |
|---|---|
| `sdmx-proxy/.../services/sdmxsource/InMemoryReadableDataLocation.java` | **New** — byte[]-backed RDL |
| `sdmx-proxy/.../services/sdmxsource/InMemoryReadableDataLocationFactory.java` | **New** — memory-only factory with cap |
| `sdmx-proxy/.../exception/ResponseTooLargeException.java` | **New** — 500 family, curated client message |
| `sdmx-proxy/.../services/adapter/config/SdmxSourceConfig.java` | **Modified** — swap bean definition |
| `sdmx-proxy/.../services/adapter/conversion/StreamingAvailabilityConversionService.java` | **Modified** — field type + import |
| `sdmx-proxy/.../services/adapter/conversion/StreamingStructureConversionService.java` | **Modified** — field type + import |
| `sdmx-proxy/.../services/adapter/conversion/StreamingDataConversionService.java` | **Modified** — field type + import |
| `sdmx-proxy/src/main/resources/application.yaml` | **Modified** — `sdmxproxy.conversion.max-in-memory-bytes` |
| `sdmx-proxy/src/test/.../services/adapter/XmlStructureReaderTest.java` | **Modified** — autowire new factory type (`:26`, `:7`) |

## No Changes Required

- `GlobalExceptionHandler` — `ResponseTooLargeException` is a `ServerErrorException`
  subclass and is handled by the existing family handler.
- `URIUtil` / `FileUtil` / sdmx-core — never touched again from these paths; the
  reference tree is read-only regardless.
- The data-path `QuotedNewlineCanonicalizingInputStream` wrapper — it is the
  `InputStream` we read fully; no change.
- `sdmx-proxy-config` module and `sdmx-proxy-config/README.md` — the cap is an
  application-level `@Value`, not a field on the `ProxyConfiguration` config
  classes, so the README schema-sync rule does not apply.

## Test Plan

### Unit tests (new)

`InMemoryReadableDataLocationTest`:
- `getInputStream_returnsFreshStreamEachCall` — two calls yield independent
  streams that each read the full content (re-readable contract).
- `getInputStream_afterClose_throws` — inherited `AbstractReadableDataLocation`
  guard rejects reads after `close()`.
- `getSize_returnsByteLength`.
- `copy_readsSameContent`.

`InMemoryReadableDataLocationFactoryTest`:
- `getReadableDataLocation_smallStream_readsAllBytes` — content round-trips.
- `getReadableDataLocation_overCap_throwsResponseTooLargeException` — cap set
  small (e.g. 16 bytes), 1 KB input → `ResponseTooLargeException`.
- `getReadableDataLocation_closesSourceStream` — pass a stream whose `close()` is
  observed; assert it was closed.
- `getReadableDataLocation_atExactCap_succeeds` — boundary (total == cap is OK,
  total > cap fails).

### Existing tests

- `XmlStructureReaderTest.shouldConvertToSdmxBeans` must be updated to autowire
  `InMemoryReadableDataLocationFactory`; behaviour (parse the bundled
  `imf_2_1_availability_response.xml`) must still pass.

### Manual / E2E verification

Registry-facing change → run E2E via the `e2e-report` skill. The original
repro is an availability query against `IMF.STA` with an empty filters array
([#105](https://github.com/epam/statgpt-sdmx-proxy/issues/105)); confirm it
returns 200 and does not log "overflow to tmp URI". A focused command:

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_2_1_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
```

Sanity-check the cap by temporarily setting `SDMX_MAX_IN_MEMORY_BYTES` to a small
value and confirming a large data query returns HTTP 500 with the curated message
(not a hang, not an OOM).

## Risks / Open Questions

- **Heap pressure.** The whole upstream payload (plus the parsed `SdmxBeans`
  graph the readers build) now lives in heap with no disk relief. For the data
  endpoint this is the largest path. Mitigation: the configurable cap fails fast;
  operators size the cap against pod heap. Default 256 MiB is a starting point,
  not a measured optimum — revisit if real data responses approach it.
- **Behavioural parity of format sniffing.** Readers call `getFormat()`, which
  on `AbstractReadableDataLocation` runs `FormatUtil.determineFileFormat` by
  re-reading the stream. Backing by `ByteArrayInputStream` supports unlimited
  re-reads, so this is strictly more reliable than the disk path. Verified the
  base class implements `getFormat()`; our subclass does not override it.
- **sdmx-core version coupling.** We extend `AbstractReadableDataLocation` from
  the runtime fork (2.4.0 per design 022; 2.3.9 is the reference tree). The base
  class is stable across these; if a future bump changes its abstract surface,
  this class is the single place to adjust. Implementing the `ReadableDataLocation`
  interface directly is the fallback but costs ~10 methods of boilerplate
  (including a `getFormat()` reimplementation) and is not recommended.
- **`SdmxSourceReadableDataLocationFactory` fully removed.** Confirmed only four
  `src` references exist (the three services + `SdmxSourceConfig`) plus one test
  (`XmlStructureReaderTest`); all are updated here. No other consumer remains.
