# Design: Single-flight for registry structure fetches

## Context

OECD (`https://sdmx.oecd.org/public/rest/`) is an SDMX 2.1 registry that caps each client IP at
roughly 20 requests per minute and answers bursts with HTTP 429 (design 039, P1). The proxy cannot
change that limit. It can only send fewer requests.

On a cold cache, the proxy sends far more structure requests than there are distinct structures:

- Concurrent requests for the same structure each miss the cache and each call the registry.
- One structure is fetched through several code paths under different cache keys, so the same
  upstream URL is called once per path even when requests arrive one at a time.

Every redundant call consumes OECD quota. Once the quota is gone, `RateLimitRetryClient` backs off
for up to 45 s per call (OECD `rateLimitRetry.maxTotalWaitMillis`), Resilience4j retries on top of
that, and requests stall for minutes.

This design adds *single-flight*: at any moment, at most one upstream call is in progress for each
distinct structure request. Concurrent callers for the same request wait for that call and receive
its result or its exception.

The cache is in-process (Caffeine) and the proxy runs as one instance. A move to Redis is planned
but out of scope. The single-flight component sits behind an interface so that a distributed
implementation can replace it without changing call sites.

## Problem

### P1 -- Concurrent cache misses each call the registry

`AdapterRouterImpl.getStructureBytes`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java:262`) reads
the raw-structures cache, and on a miss calls the registry and writes the result. Nothing
coordinates two threads that miss at the same time. N concurrent callers produce N upstream calls.

### P2 -- One structure, several upstream calls

These four call sites send structure requests to the registry:

| Call site | Line | Cache consulted |
|-----------|------|-----------------|
| `AdapterRouterImpl.getStructuresConversion` | 184 | Ready response only |
| `AdapterRouterImpl.getStructuresBypass` | 242 | Ready response only |
| `AdapterRouterImpl.getStructureBytes` (via `getFixedStructureStream`, line 172) | 270 | Raw structures |
| `MetadataAttributeUsageFolder.fetchMsd` (`services/fixture/structure/MetadataAttributeUsageFolder.java:162`) | 162 | None |

`RouterAvailabilityEmulationContext.fetchStructure` (`AdapterRouterImpl.java:619`) also calls
`genericRegistryAdapter.getStructures` directly, but nothing calls `fetchStructure` today.

The conversion path never reads the raw-structures cache. A DSD requested as
`structure+json;version=2.0.0`, then as `structure+xml;version=2.1`, then used internally by a data
request through `getSdmxBeans`, costs three upstream calls for one URL. Bypass is disabled for
almost every registry, so conversion is the main client path.

### P3 -- Raw cache stores post-fixture bytes, conversion needs pre-fixture bytes

`getStructureBytes` caches bytes after `StructureFixtureService.applyFixtures`. The conversion
path captures `metadataAttributeUsages` (`MetadataAttributeUsagePreserver.capture`) and folds them
for XML 2.1 (`MetadataAttributeUsageFolder.foldForXml21`) from bytes *before* fixtures, and then
applies fixtures. The `DSD_ATTRIBUTE_ATTACHMENT_LEVEL` fixture rewrites
`metadataAttributeUsages[].attributeRelationship`
(`services/fixture/structure/DsdAttributeAttachmentLevelJsonFixture.java:84`). If conversion read
today's raw cache, the captured usages would change and so would the client output.

### P4 -- Cache keys do not match what goes over the wire

`CacheKeyGenerator.generateStructureKey`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java`) builds the
key from the translated query before the adapter normalizes it:

- For SDMX 2.1, `GenericRegistryAdapterImpl.getStructures21` (line 85) passes every path slot
  through `Sdmx21QueryNormalizer.toSdmx21PathSlot` (`*`, `""` and `null` become `all`; `+`
  becomes `latest`) and the type through `toSdmx21StructureType` (`dataconstraint` becomes
  `contentconstraint`). Requests that produce the same URL get different keys, so single-flight
  keyed on today's key does not merge them.
- The key identifies the registry by name only. It omits the SDMX API version, the registry return
  format, and the endpoint URL. Two version configurations of one registry share keys, which is
  why `CacheService.invalidateAll()` has to run on every configuration change.

## Current architecture

```
getStructures (client) ── ready-response cache ─miss─┬─ conversion ── genericRegistryAdapter.getStructures ──► registry
                                                     └─ bypass ───── genericRegistryAdapter.getStructures ──► registry

getSdmxBeans (internal) ── raw cache ─miss─ genericRegistryAdapter.getStructures ── fixtures ── put(raw, post-fixture) ──► registry

MetadataAttributeUsageFolder.fetchMsd ──── genericRegistryAdapter.getStructures ─────────────────────────────► registry
```

## Solution

Route every structure fetch through one new service, `RawStructureFetcher`. It owns the raw cache
and the single-flight, and it is the only class that calls `GenericRegistryAdapter.getStructures`.

```
getStructures (client) ── ready-response cache ─miss─┬─ conversion ─┐
                                                     └─ bypass ─────┤
getSdmxBeans (internal) ─────────────────────────────── + fixtures ─┤
MetadataAttributeUsageFolder.fetchMsd ──────────────────────────────┤
                                                                    ▼
                                       RawStructureFetcher.fetch(query)
                                         1. raw cache hit ──────────────────────────► bytes
                                         2. rate-limit cooldown? ───────────────────► 429
                                         3. RequestCoalescer.coalesce(key, load)
                                              leader: re-check cache, call registry,
                                                      put(raw, registry bytes verbatim)
                                              others: wait on the leader's future
```

The design rests on these decisions, agreed with the user before writing:

1. **Single-flight at the raw level only.** Ready responses are a local conversion and cost no
   registry quota. Identical concurrent ready-response misses each run their own conversion, but
   all of them share one upstream call.
2. **The raw cache holds registry bytes verbatim.** Fixtures run after the cache read, in
   `getSdmxBeans`. This resolves P3 without a second cache domain and keeps every client path
   byte-identical to today: conversion still captures usages from pre-fixture bytes, bypass still
   returns unfixed bytes, and `getSdmxBeans` still parses post-fixture bytes. The cost is one
   fixture pass per `getSdmxBeans` call, next to the full parse that already runs on every call.
3. **Waiters wait on a `CompletableFuture`, not on the cache.** If the upstream call fails, or the
   cache write is dropped, waiters still complete immediately with the exception or the bytes.
4. **The leader's work runs on the coalescer's own executor.** Spring cancels an async request
   with an interrupt on `spring.mvc.async.request-timeout`, and `RateLimitRetryClient` aborts its
   429 backoff when interrupted (`registry/api/http/RateLimitRetryClient.java`, the `sleeper.sleep`
   branch). Running the call on the first caller's thread would let that caller's timeout or
   disconnect kill the work everyone else waits for. On its own executor, the call always runs to
   completion and fills the cache, even if every waiter has gone.
5. **Bounded wait.** Each waiter calls `future.get(waitTimeout)`. The default is 590 s, 10 s under
   `request-timeout` (600 s), so the proxy's own 503 reaches the client before Spring's async
   timeout fires. Timing out abandons only that waiter.
6. **Negative caching for exhausted 429 only.** When an upstream call still ends in 429 after
   `RateLimitRetryClient` has spent its budget, the key enters a cooldown (default 30 s). During
   the cooldown, `fetch` fails fast with 429 without calling the registry. Other errors are not
   cached: the next call retries.
7. **Canonical key = the wire request.** The raw key adds the SDMX API version, the return format,
   and a hash of the structure endpoint URL, and it passes 2.1 slots through
   `Sdmx21QueryNormalizer` exactly as the adapter does.
8. **Metrics through the OpenTelemetry API** already on the classpath, mirrored in INFO logs,
   because `OTEL_SDK_DISABLED` defaults to `true` and the meters are then no-ops.

### Key canonicalization audit

The following table lists each source of key divergence on the structure path and how the new key
handles it:

| Divergence | Same wire request? | Today | After |
|------------|--------------------|-------|-------|
| 2.1 version `+` / `latest` | Yes, both send `latest` | Two keys | One key |
| 2.1 agency, id, or version `*` / `""` / `null` / `all` | Yes, all send `all` | Up to four keys | One key |
| 2.1 type `dataconstraint` / `contentconstraint` | Yes, both send `contentconstraint` | Two keys | One key |
| 3.0 version `+` / `~` | No, different SDMX 3.0 semantics | Two keys | Two keys |
| `references` absent / `none` | No, the parameter is present in one URL only | Two keys | Two keys |
| Agency or id letter case | No, SDMX identifiers are case-sensitive | Distinct | Distinct |
| SDMX API version of the registry | No, different endpoint | **Collides** | Distinct |
| Registry return format | No, different `Accept` | **Collides** | Distinct |
| Structure endpoint URL | No | **Collides** | Distinct |
| Query-parameter order, trailing slash | n/a: Feign builds the URL from the method signature and one configured base URL | -- | -- |

The `references` row stays conservative on purpose: absent and `none` mean the same thing in the
spec, but the registry receives different URLs, and design 039 P2 shows that NSI treats `none`
differently on availability. Merging them is not this design's call.

The URL hash also closes a race with `POST /config`. A flight that started under the old
configuration and finishes after `invalidateAll()` writes under the old URL's key, which no new
request reads. Fixtures no longer affect raw bytes, so no other configuration change can make a raw
entry stale.

## Implementation plan

### Step 1: Configuration properties

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/SingleFlightProperties.java`

```java
package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

import java.time.Duration;

/**
 * Single-flight settings for registry structure fetches -- see design 042.
 */
@Data
public class SingleFlightProperties {

    /**
     * Upper bound on how long one caller waits for a shared upstream call. Kept under
     * {@code spring.mvc.async.request-timeout} so the proxy's own 503 reaches the client first.
     * Expiry abandons only the waiting caller; the upstream call runs on and fills the cache.
     */
    private Duration waitTimeout = Duration.ofSeconds(590);

    /**
     * How long a key that ended in HTTP 429 after the full retry budget fails fast without
     * calling the registry.
     */
    private Duration rateLimitCooldown = Duration.ofSeconds(30);
}
```

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/CacheProperties.java`.
Add the field after `ttl`:

```java
    /**
     * Single-flight configuration for registry structure fetches
     */
    private SingleFlightProperties singleFlight = new SingleFlightProperties();
```

**Modified:** `sdmx-proxy/src/main/resources/application.yaml`. Under `sdmxproxy.cache`, after the
`ttl` block:

```yaml
    singleFlight:
      waitTimeout: PT590S
      rateLimitCooldown: PT30S
```

### Step 2: Exceptions

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/RegistryResponseTimeoutException.java`

```java
package com.epam.sdmxproxy.exception;

/**
 * Thrown to a caller whose wait for a shared registry call exceeded the single-flight wait
 * timeout. The call itself keeps running and fills the cache -- see design 042.
 */
public class RegistryResponseTimeoutException extends ServiceUnavailableException {

    private static final String CLIENT_MESSAGE = "The registry has not answered yet. The request is still in progress; retry later.";

    public RegistryResponseTimeoutException(String message) {
        super(message);
    }

    public RegistryResponseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return CLIENT_MESSAGE;
    }
}
```

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/RegistryCoolingDownException.java`

```java
package com.epam.sdmxproxy.exception;

/**
 * Thrown while a structure key is in its rate-limit cooldown: the registry answered HTTP 429
 * after the full retry budget, and calling it again immediately would only spend more quota.
 */
public class RegistryCoolingDownException extends TooManyRequestsException {

    public RegistryCoolingDownException(String message) {
        super(message);
    }

    public RegistryCoolingDownException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`GlobalExceptionHandler` dispatches on the family types, so the first maps to 503 and the second to
429 with no handler changes.

### Step 3: `RequestCoalescer` interface and in-process implementation

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/singleflight/RequestCoalescer.java`

```java
package com.epam.sdmxproxy.services.singleflight;

import java.util.function.Supplier;

/**
 * Collapses concurrent calls that share a key into one execution of {@code loader}. Every caller
 * receives the same bytes or the same exception. Implementations decide where the in-flight
 * registry lives: in this process, or in a shared store for a multi-instance deployment.
 */
public interface RequestCoalescer {

    /**
     * Returns the loader's result for {@code key}, running {@code loader} only if no call for
     * {@code key} is already in flight.
     *
     * @throws com.epam.sdmxproxy.exception.RegistryResponseTimeoutException if the wait exceeds the
     *         configured timeout; the shared call is not cancelled
     */
    byte[] coalesce(String key, Supplier<byte[]> loader);
}
```

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/singleflight/InProcessRequestCoalescer.java`

```java
package com.epam.sdmxproxy.services.singleflight;

import com.epam.sdmxproxy.exception.RegistryResponseTimeoutException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Slf4j
@Service
public class InProcessRequestCoalescer implements RequestCoalescer {

    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration waitTimeout;
    private final LongCounter coalescedCounter;
    private final LongCounter waitTimeoutCounter;

    public InProcessRequestCoalescer(CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry) {
        this.waitTimeout = cacheProperties.getSingleFlight().getWaitTimeout();
        Meter meter = openTelemetry.getIfAvailable(OpenTelemetry::noop).getMeter("sdmx-proxy.single-flight");
        meter.gaugeBuilder("sdmxproxy.singleflight.inflight").ofLongs().setDescription("Upstream calls currently in flight").buildWithCallback(m -> m.record(inFlight.size()));
        this.coalescedCounter = meter.counterBuilder("sdmxproxy.singleflight.coalesced").setDescription("Calls served by joining an in-flight upstream call").build();
        this.waitTimeoutCounter = meter.counterBuilder("sdmxproxy.singleflight.wait_timeouts").setDescription("Callers that gave up waiting for a shared upstream call").build();
    }

    @Override
    public byte[] coalesce(String key, Supplier<byte[]> loader) {
        CompletableFuture<byte[]> candidate = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inFlight.putIfAbsent(key, candidate);
        if (existing == null) {
            start(key, candidate, loader);
            return await(key, candidate);
        }
        coalescedCounter.add(1);
        log.info("Single-flight: joined in-flight call for {}", key);
        return await(key, existing);
    }

    private void start(String key, CompletableFuture<byte[]> flight, Supplier<byte[]> loader) {
        // Registered before the task starts, so removal runs on every outcome. remove(key, value)
        // never evicts a newer flight that reused the key.
        flight.whenComplete((bytes, error) -> inFlight.remove(key, flight));
        Runnable task = Context.current().wrap(() -> {
            try {
                flight.complete(loader.get());
            } catch (Throwable t) {
                flight.completeExceptionally(t);
            }
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            flight.completeExceptionally(e);
        }
    }

    private byte[] await(String key, CompletableFuture<byte[]> flight) {
        try {
            return flight.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            waitTimeoutCounter.add(1);
            log.warn("Single-flight: gave up waiting {} for {}; the upstream call continues", waitTimeout, key);
            throw new RegistryResponseTimeoutException("Timed out after " + waitTimeout + " waiting for registry call " + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnexpectedStateException("Interrupted while waiting for registry call " + key, e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new UnexpectedStateException("Registry call failed with a checked exception", cause);
    }

    int inFlightCount() {
        return inFlight.size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

**Key points:**

- **`putIfAbsent`, not `computeIfAbsent`.** A loader that finishes very fast runs the
  `whenComplete` removal on the same key. Inside a `computeIfAbsent` mapping function, that
  re-entrant update throws `IllegalStateException: Recursive update`. With `putIfAbsent`, the
  future is in the map before the task starts.
- **Never call `orTimeout`, `completeOnTimeout`, or `cancel` on the shared future.** All three
  change the future for every waiter. The per-waiter bound is `get(timeout)`.
- **The first caller waits like everyone else.** It never runs the loader on its own thread (see
  decision 4).
- **`Context.current().wrap`** carries the OpenTelemetry context into the virtual thread, so
  `CorrelationIdInterceptor` sees the leader's trace ID on the outbound request. Waiters joining
  later keep their own trace IDs; the INFO log line links them by key.
- **Unobserved exceptions.** `CompletableFuture` does not report an exception nobody reads, so a
  flight whose waiters all left needs no extra handling. The loader's own logging covers it.
- **Interrupted waiters.** An interrupt restores the flag and throws. The shared future is
  untouched.
- **No new dependencies.** `opentelemetry-api` and `opentelemetry-context` come in through
  `io.opentelemetry:opentelemetry-sdk` (`sdmx-proxy/build.gradle:139`). The OpenTelemetry Spring
  Boot starter is expected to register an `OpenTelemetry` bean even with the SDK disabled, but
  this was not verified; `getIfAvailable(OpenTelemetry::noop)` covers both cases.

### Step 4: Canonical raw-structure key

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java`

Replace `generateStructureKey(TranslatedStructureQuery)` and update `generateResponseKey`, which
derives its base from the structure key. The normalizer is a parameter, following the precedent of
`generateAvailabilityEmulationKey` taking `AvailabilityQueryCanonicalizer` (design 040,
deviation 3).

```java
    /**
     * Generate cache key for raw registry structure bytes. The key identifies the request as it
     * goes over the wire, so requests that produce the same URL share a key -- see design 042.
     * Format: structure:{type}:{registryName}:{sdmxVersion}:{returnFormat}:{md5(structureUrl)}:{agencyId}:{resourceId}:{version}:{references}:{detail}
     * <p>
     * For SDMX 2.1, the type and path slots go through the same {@link Sdmx21QueryNormalizer}
     * calls as {@code GenericRegistryAdapterImpl.getStructures21}. References and detail are sent
     * verbatim by both versions, so they stay verbatim here.
     */
    public static String generateStructureKey(TranslatedStructureQuery query, Sdmx21QueryNormalizer normalizer) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        SdmxVersion sdmxVersion = versionConfig.getSdmxVersion();
        Structure structure = query.getStructure();
        boolean sdmx21 = sdmxVersion == SdmxVersion.SDMX_2_1;

        String type = sdmx21 ? normalizer.toSdmx21StructureType(structure.type()) : structure.type();
        String agencyId = sdmx21 ? normalizer.toSdmx21PathSlot(structure.agency()) : nullToEmpty(structure.agency());
        String resourceId = sdmx21 ? normalizer.toSdmx21PathSlot(structure.id()) : nullToEmpty(structure.id());
        String version = sdmx21 ? normalizer.toSdmx21PathSlot(structure.version()) : nullToEmpty(structure.version());
        String structureUrl = versionConfig.getStructureEndpointConfig() != null ? versionConfig.getStructureEndpointConfig().getUrl() : null;
        String returnFormat = query.getRegistryReturnFormat() != null ? query.getRegistryReturnFormat().name() : "";

        return STRUCTURE_KEY_PREFIX
                + type + KEY_SEPARATOR
                + query.getRegistryConfiguration().getName() + KEY_SEPARATOR
                + sdmxVersion + KEY_SEPARATOR
                + returnFormat + KEY_SEPARATOR
                + md5Hash(nullToEmpty(structureUrl)) + KEY_SEPARATOR
                + agencyId + KEY_SEPARATOR
                + resourceId + KEY_SEPARATOR
                + version + KEY_SEPARATOR
                + nullToEmpty(query.getReferences()) + KEY_SEPARATOR
                + nullToEmpty(query.getDetail());
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
```

`generateResponseKey` gains a trailing `Sdmx21QueryNormalizer normalizer` parameter and calls
`generateStructureKey(query, normalizer)`. The rest of its body is unchanged. Update the class
javadoc's key formats to match.

New imports: `com.epam.sdmxproxy.configuration.data.SdmxVersion`,
`com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration`,
`com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer`.

**Key points:**

- The new key has 11 segments where the old had eight, and a different value in the third
  segment, so no old Redis entry can match a new key. That matters because old entries hold
  post-fixture bytes and new entries hold registry bytes.
- `generateFanOutResponseKey`, `generateLimitEmulationKey`, and
  `generateAvailabilityEmulationKey` are out of this design's scope and stay as they are.

### Step 5: `RawStructureFetcher`

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcher.java`

```java
package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;

/**
 * The single entry point for structure bytes from a registry. Returns the registry's response
 * verbatim -- no fixtures -- served from the raw-structures cache when possible, and coalesced so
 * that concurrent callers for one request share one upstream call. See design 042.
 */
public interface RawStructureFetcher {

    /**
     * @return the registry's bytes, or an empty array when the registry returned no body
     */
    byte[] fetch(TranslatedStructureQuery query);
}
```

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImpl.java`

```java
package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.exception.RegistryCoolingDownException;
import com.epam.sdmxproxy.exception.StructureConversionException;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.singleflight.RequestCoalescer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import feign.FeignException;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
public class RawStructureFetcherImpl implements RawStructureFetcher {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final AttributeKey<String> REGISTRY = AttributeKey.stringKey("registry");

    private final GenericRegistryAdapter genericRegistryAdapter;
    private final CacheService cacheService;
    private final RequestCoalescer requestCoalescer;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;
    private final Duration rateLimitCooldown;
    private final Cache<String, Boolean> coolingDown;
    private final LongCounter upstreamCounter;
    private final LongCounter cooldownRejectionCounter;

    public RawStructureFetcherImpl(GenericRegistryAdapter genericRegistryAdapter, CacheService cacheService, RequestCoalescer requestCoalescer, Sdmx21QueryNormalizer sdmx21QueryNormalizer, CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry) {
        this(genericRegistryAdapter, cacheService, requestCoalescer, sdmx21QueryNormalizer, cacheProperties, openTelemetry, Ticker.systemTicker());
    }

    RawStructureFetcherImpl(GenericRegistryAdapter genericRegistryAdapter, CacheService cacheService, RequestCoalescer requestCoalescer, Sdmx21QueryNormalizer sdmx21QueryNormalizer, CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry, Ticker ticker) {
        this.genericRegistryAdapter = genericRegistryAdapter;
        this.cacheService = cacheService;
        this.requestCoalescer = requestCoalescer;
        this.sdmx21QueryNormalizer = sdmx21QueryNormalizer;
        this.rateLimitCooldown = cacheProperties.getSingleFlight().getRateLimitCooldown();
        this.coolingDown = Caffeine.newBuilder().expireAfterWrite(rateLimitCooldown).ticker(ticker).build();
        Meter meter = openTelemetry.getIfAvailable(OpenTelemetry::noop).getMeter("sdmx-proxy.single-flight");
        this.upstreamCounter = meter.counterBuilder("sdmxproxy.registry.structure_requests").setDescription("Structure requests actually sent to a registry").build();
        this.cooldownRejectionCounter = meter.counterBuilder("sdmxproxy.singleflight.cooldown_rejections").setDescription("Structure fetches refused during a rate-limit cooldown").build();
    }

    @Override
    public byte[] fetch(TranslatedStructureQuery query) {
        String key = CacheKeyGenerator.generateStructureKey(query, sdmx21QueryNormalizer);
        Optional<byte[]> cached = cacheService.getRawStructures(key);
        if (cached.isPresent()) {
            log.debug("Cache hit for raw structures: {}", key);
            return cached.get();
        }
        throwIfCoolingDown(key);
        return requestCoalescer.coalesce(key, () -> load(query, key));
    }

    private byte[] load(TranslatedStructureQuery query, String key) {
        // The value may have landed while this flight waited for an executor slot or, after the
        // move to Redis, while another instance fetched it.
        Optional<byte[]> cached = cacheService.getRawStructures(key);
        if (cached.isPresent()) {
            log.debug("Raw structures appeared before the upstream call: {}", key);
            return cached.get();
        }
        throwIfCoolingDown(key);

        String registryName = query.getRegistryConfiguration().getName();
        upstreamCounter.add(1, Attributes.of(REGISTRY, registryName));
        log.info("Single-flight: calling registry {} for {}", registryName, key);
        try (InputStream in = genericRegistryAdapter.getStructures(query)) {
            if (in == null) {
                log.debug("Registry returned no body (e.g. HTTP 204) for raw structures: {}", key);
                return new byte[0];
            }
            byte[] bytes = in.readAllBytes();
            cacheService.putRawStructures(key, bytes);
            return bytes;
        } catch (FeignException e) {
            if (e.status() == TOO_MANY_REQUESTS) {
                coolingDown.put(key, Boolean.TRUE);
                log.warn("Registry {} still answered 429 after the retry budget for {}; cooling down for {}", registryName, key, rateLimitCooldown);
            }
            throw e;
        } catch (IOException e) {
            throw new StructureConversionException("Failed to read structures", e);
        }
    }

    private void throwIfCoolingDown(String key) {
        if (coolingDown.getIfPresent(key) != null) {
            cooldownRejectionCounter.add(1);
            throw new RegistryCoolingDownException("Registry rate limit cooldown active for " + key);
        }
    }
}
```

**Key points:**

- `FeignException.status() == 429` covers both decoder outcomes. With the default `ErrorDecoder`,
  a 429 that carries `Retry-After` (OECD sends `Retry-After: 0`) decodes to `RetryableException`,
  which extends `FeignException` and keeps the status.
- The cooldown is per key and per process. A per-registry cooldown would protect quota better, but
  it would also block structures that are already cached on other paths and wouldn't help data
  requests, which bypass this service. Deferred.
- The cooldown check before `coalesce` avoids starting a flight; the check inside `load` covers a
  flight that queued behind a 429.
- `upstreamCounter` counts upstream calls, not attempts. Retries inside `RateLimitRetryClient` and
  Resilience4j are not visible here; their existing WARN logs cover them.

### Step 6: Route `AdapterRouterImpl` through the fetcher

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

1. Add the field `private final RawStructureFetcher rawStructureFetcher;` and
   `private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;`. Constructor injection through
   the existing `@RequiredArgsConstructor`.
2. `getStructures` (line 152): pass `sdmx21QueryNormalizer` to `generateResponseKey`.
3. `getStructuresConversion` (line 182): fetch before the conversion `try`, so fetch failures
   (`FeignException`, `RegistryResponseTimeoutException`, `RegistryCoolingDownException`) reach
   `GlobalExceptionHandler` unwrapped instead of becoming `StructureConversionException`:

   ```java
   private StreamingResponseBody getStructuresConversion(TranslatedStructureQuery query, SdmxFormat returnFormat, MediaType requestedMediaType, String responseKey) {
       return outputStream -> {
           byte[] rawBytes = rawStructureFetcher.fetch(query);
           if (rawBytes.length == 0) {
               return;
           }
           try {
               List<FixtureConfiguration<StructureFixtureType>> fixtures = query.getVersionConfiguration().getStructureEndpointConfig().getFixtures();
               // ... markerEnabled / preserveUsages / foldUsages / injectUsagesXml30 unchanged ...

               Map<String, JsonNode> capturedUsages = Map.of();
               InputStream forFixtures;
               if (preserveUsages || injectUsagesXml30) {
                   capturedUsages = metadataAttributeUsagePreserver.capture(rawBytes);
                   forFixtures = new ByteArrayInputStream(rawBytes);
               } else if (foldUsages) {
                   forFixtures = new ByteArrayInputStream(metadataAttributeUsageFolder.foldForXml21(rawBytes, query));
               } else {
                   forFixtures = new ByteArrayInputStream(rawBytes);
               }
               // ... from "try (InputStream postFixtureStream = ..." to the end: unchanged ...
           } catch (FeignException e) {
               log.warn("Failure on registry side.", e);
               throw e;
           } catch (Exception e) {
               log.error("Failed to convert structures from {} to {}", returnFormat, requestedMediaType, e);
               throw new StructureConversionException("Failed to convert structures", e);
           }
       };
   }
   ```

   The `catch (FeignException e)` stays: `foldForXml21` swallows its own MSD fetch errors, but
   nothing guarantees that every future fixture does.

4. `getStructuresBypass` (line 240):

   ```java
   return outputStream -> {
       byte[] bytes = rawStructureFetcher.fetch(query);
       outputStream.write(bytes);
       cacheService.putReadyResponse(responseKey, bytes);
   };
   ```

5. `getSdmxBeans` (line 251): fetch raw bytes, then apply fixtures, then parse. Delete
   `getStructureBytes` and `getFixedStructureStream`.

   ```java
   @Override
   public SdmxBeans getSdmxBeans(TranslatedStructureQuery query) {
       byte[] rawBytes = rawStructureFetcher.fetch(query);
       byte[] structures = rawBytes.length == 0 ? rawBytes : applyStructureFixtures(query, rawBytes);
       if (structures.length == 0) {
           log.debug("Empty structures payload for {}:{}; returning empty SdmxBeans", query.getRegistryConfiguration().getName(), query.getStructure());
           return new SdmxBeansImpl();
       }
       SdmxFormat structureReturnFormat = query.getRegistryReturnFormat();
       return streamingStructureConversionService.parseStructures(new ByteArrayInputStream(structures), structureReturnFormat);
   }

   private byte[] applyStructureFixtures(TranslatedStructureQuery query, byte[] rawBytes) {
       List<FixtureConfiguration<StructureFixtureType>> fixtures = query.getVersionConfiguration().getStructureEndpointConfig().getFixtures();
       try (InputStream fixed = fixtureService.applyFixtures(new ByteArrayInputStream(rawBytes), query.getRegistryReturnFormat(), fixtures)) {
           return fixed != null ? fixed.readAllBytes() : new byte[0];
       } catch (IOException e) {
           throw new StructureConversionException("Failed to apply structure fixtures", e);
       }
   }
   ```

   The post-fixture bytes are materialized before parsing, as today: `parseStructures` received a
   `ByteArrayInputStream` before this change too.

6. `RouterAvailabilityEmulationContext.fetchStructure` (line 619):
   `return new ByteArrayInputStream(rawStructureFetcher.fetch(query));`. It has no callers, but
   leaving a direct registry call in place would invite the next caller to bypass single-flight.

7. Remove the now-unused `CacheKeyGenerator.generateStructureKey` call and any unused imports.

### Step 7: Route the MSD side-fetch through the fetcher

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageFolder.java`

Replace the `GenericRegistryAdapter genericRegistryAdapter` field with
`RawStructureFetcher rawStructureFetcher`, and in `fetchMsd` replace the `try (InputStream in = ...)`
block:

```java
        try {
            byte[] bytes = rawStructureFetcher.fetch(msdQuery);
            if (bytes.length == 0) {
                return null;
            }
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode arr = root.path(DATA).path(METADATA_STRUCTURES);
            return (arr.isArray() && !arr.isEmpty()) ? arr.get(0) : null;
        } catch (Exception e) {
            log.warn("MSD side-fetch failed for {}:{}: {}", urn.getAgency(), urn.getId(), e.getMessage());
            return null;
        }
```

The MSD was previously fetched on every fold with no caching. It is now cached and coalesced as
well. The fold runs only for registries whose return format is SDMX-JSON 2.0, so OECD is not
affected; the change is for consistency.

## No changes required

These files need no modification:

- **`GenericRegistryAdapterImpl.java`** -- still performs the wire call; only its callers change.
- **`RateLimitRetryClient.java`, `Resilience4jComponentFactory.java`** -- their retries run inside
  the flight because the flight wraps the adapter call.
- **`RateLimitingInvocationHandlerFactory.java`** -- the proxy's own limiter is out of scope.
- **`CacheService.java`, `InMemoryCacheService.java`, `RedisCacheService.java`** -- the
  raw-structures domain keeps its interface and TTL; only the meaning of the stored bytes changes.
- **`GlobalExceptionHandler.java`** -- the new exceptions use the existing 503 and 429 families.
- **`ProxyConfigurationProviderImpl.java`, `ConfigServerConfigExtractor.java`** -- keep calling
  `invalidateAll()`; the coalescer needs no reset (see edge case 7).
- **`sdmx-proxy-config/README.md`** -- the new properties live in the Spring cache properties, not
  in `sdmx-proxy-config`.
- **Data and availability paths** -- data and passthrough availability are streamed and never
  cached, so they stay out of scope.

## Files affected

| File | Change type | Description |
|------|-------------|-------------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/singleflight/RequestCoalescer.java` | New | Single-flight interface |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/singleflight/InProcessRequestCoalescer.java` | New | `ConcurrentHashMap` + `CompletableFuture` implementation, own executor, metrics |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcher.java` | New | Single entry point for structure bytes |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImpl.java` | New | Raw cache, cooldown, coalescing, upstream counter |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/SingleFlightProperties.java` | New | `waitTimeout`, `rateLimitCooldown` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/RegistryResponseTimeoutException.java` | New | 503 for an expired wait |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/RegistryCoolingDownException.java` | New | 429 during cooldown |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/CacheProperties.java` | Modified | Add `singleFlight` |
| `sdmx-proxy/src/main/resources/application.yaml` | Modified | Add `sdmxproxy.cache.singleFlight` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java` | Modified | Canonical structure key; normalizer parameter |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java` | Modified | All structure fetches through `RawStructureFetcher`; fixtures after cache read |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageFolder.java` | Modified | MSD side-fetch through `RawStructureFetcher` |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/singleflight/InProcessRequestCoalescerTest.java` | New | Concurrency tests |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImplTest.java` | New | Cache, cooldown, and end-to-end coalescing tests |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/cache/CacheKeyGeneratorTest.java` | Modified | Canonicalization tests |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImplTest.java` | Modified | Constructor; mock `RawStructureFetcher` instead of `genericRegistryAdapter.getStructures` |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsageFolderTest.java` | Modified | Mock `RawStructureFetcher` instead of `GenericRegistryAdapter` |

## Edge cases

1. **Every waiter leaves.** Waiters time out, disconnect, or are interrupted by Spring's async
   timeout. The task keeps running on the coalescer's executor, writes the cache, and the
   `whenComplete` hook removes the in-flight entry.
2. **Registry returns no body (HTTP 204).** `load` returns an empty array and does not write the
   cache, matching today's `getStructureBytes`. Every waiter sees empty; the next call retries.
   Conversion now returns early on an empty array; previously an empty but non-null stream reached
   the converter and failed there.
3. **Upstream 5xx, network error, or open circuit breaker.** All waiters get the same exception
   instance, no cooldown, and the next call starts a new flight.
4. **429 after the full retry budget.** Waiters get the `FeignException` (429 to the client). New
   calls get `RegistryCoolingDownException` (429) for `rateLimitCooldown`, then retry.
5. **Proxy rate limiter rejects (`RateLimitExceededException`).** Propagated to all waiters, no
   cooldown. The proxy limiter is a local setting; cooling down on it would add a second, unrelated
   delay.
6. **Ready-response misses in parallel.** N identical client requests each run their own
   conversion after one shared fetch. CPU only, no quota.
7. **`POST /config` while a flight runs.** `invalidateAll()` clears the caches. The running
   flight writes under its own key. If the structure URL, API version, or return format changed,
   that key is unreachable from the new configuration and expires by TTL. If only fixtures or
   resilience settings changed, the bytes are still valid, because fixtures run after the read.
   New requests for an unchanged key join the running flight, which is correct for the same
   reason.
8. **Waiter thread pools.** Client structure requests wait on Spring's async executor; internal
   `getSdmxBeans` calls from data and availability controllers wait on Tomcat request threads. A
   long OECD backoff therefore holds those threads for up to `waitTimeout`, as it does today for a
   single request. The upstream call itself never depends on those pools, so a full pool can't
   deadlock the flight.
9. **Fast loader.** A loader that completes before `start` returns triggers `whenComplete`
   synchronously. `putIfAbsent` placed the future first, so the removal finds it; no recursive
   map update.
10. **Fan-out.** `runFanOutLegs` calls `getSdmxBeans` per registry, so each leg coalesces with any
    concurrent request for the same registry structure. The fan-out ready-response key is
    unchanged.

## Verification

### Unit tests

Concurrency tests release the loader with a `CountDownLatch` and wait with bounded `await`; no
test uses `Thread.sleep` as synchronization. Each test class asserts `inFlightCount() == 0` in an
`@AfterEach`, which covers the "in-flight registry empty after all tests" requirement.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/singleflight/InProcessRequestCoalescerTest.java`

| Test method | Description |
|-------------|-------------|
| `coalesce_fiftyConcurrentCallersSameKey_runLoaderOnce()` | 50 callers on one key, loader blocked on a latch until all 50 are waiting; loader runs once, all 50 get the same array instance, coalesced counter = 49 |
| `coalesce_tenKeysFiveCallersEach_runLoaderTenTimes()` | 10 keys x 5 callers; exactly 10 loader runs, each caller gets its own key's value |
| `coalesce_someWaitersInterrupted_othersStillGetResult()` | Interrupt 20 of 50 waiting threads; the other 30 get the value, the loader completes once, the interrupted threads see `UnexpectedStateException` with the interrupt flag set |
| `coalesce_allWaitersGone_loaderStillCompletes()` | Wait timeout 50 ms, loader blocked longer; every caller gets `RegistryResponseTimeoutException`, then the loader is released, completes, and the map empties |
| `coalesce_loaderThrows_allWaitersGetSameException()` | Loader throws a `FeignException`; every waiter receives the same instance, the map is empty, and a following call runs the loader again |
| `coalesce_checkedExceptionCause_wrappedInUnexpectedState()` | Loader throws a sneaky checked exception; waiters get `UnexpectedStateException` with it as the cause |
| `coalesce_afterCompletion_nextCallRunsNewFlight()` | Two sequential calls on one key run the loader twice; the coalescer itself does not cache |
| `coalesce_immediateLoader_noRecursiveUpdate()` | 10,000 sequential calls with a loader that returns at once; no `IllegalStateException`, map empty |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImplTest.java`

Uses a real `InProcessRequestCoalescer` and `InMemoryCacheService`, a mocked `GenericRegistryAdapter`,
and a fake Caffeine `Ticker` for the cooldown.

| Test method | Description |
|-------------|-------------|
| `fetch_fiftyConcurrentColdCacheCalls_oneUpstreamCall()` | 50 concurrent `fetch` calls on an empty cache, adapter blocked on a latch; `getStructures` called once, all get identical bytes, cache populated |
| `fetch_tenStructuresFiveCallersEach_tenUpstreamCalls()` | Upstream calls equal distinct structures |
| `fetch_afterFlightCompletes_servedFromCache()` | A second sequential call does not touch the adapter |
| `fetch_storesRegistryBytesVerbatim()` | Cached bytes equal the adapter's bytes; `StructureFixtureService` is not involved |
| `fetch_valueAppearsBeforeUpstream_recheckSkipsCall()` | Cache mock returns empty, then present; adapter never called |
| `fetch_nullStream_returnsEmptyAndDoesNotCache()` | HTTP 204 behaviour |
| `fetch_exhausted429_entersCooldown()` | Adapter throws a 429 `FeignException`; the next call throws `RegistryCoolingDownException` without calling the adapter; after advancing the ticker past the cooldown, the adapter is called again |
| `fetch_retryable429WithRetryAfter_entersCooldown()` | Same with `RetryableException` carrying status 429 |
| `fetch_upstream500_noCooldown()` | The next call goes to the adapter |
| `fetch_upstreamFailure_inFlightRemovedAndNextCallRetries()` | Exception propagation plus retry on the next call |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/cache/CacheKeyGeneratorTest.java`

| Test method | Description |
|-------------|-------------|
| `generateStructureKey_sdmx21_plusAndLatestShareKey()` | `+` and `latest` |
| `generateStructureKey_sdmx21_wildcardsShareKey()` | `*`, `""`, `null`, `all` in each slot |
| `generateStructureKey_sdmx21_dataconstraintAndContentconstraintShareKey()` | Type normalization |
| `generateStructureKey_sdmx30_plusAndTildeDiffer()` | No normalization for 3.0 |
| `generateStructureKey_referencesAbsentAndNoneDiffer()` | Conservative `references` handling |
| `generateStructureKey_agencyCasePreserved()` | `OECD` and `oecd` differ |
| `generateStructureKey_differsBySdmxVersion()` | Same registry name, 2.1 vs 3.0 |
| `generateStructureKey_differsByReturnFormat()` | JSON 2.0 vs XML 2.1 |
| `generateStructureKey_differsByStructureUrl()` | Same name, different URL |
| `generateResponseKey_usesCanonicalStructureKey()` | `+` and `latest` share a 2.1 response key for the same `Accept` |

Use the real `Sdmx21QueryNormalizerImpl` in these tests, so they fail if the adapter's
normalization and the key's drift apart.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImplTest.java`

Add `rawStructureFetcher` and `new Sdmx21QueryNormalizerImpl()` to the constructor call. Rewrite
the two existing tests that stub `genericRegistryAdapter.getStructures` to stub
`rawStructureFetcher.fetch` instead. New tests:

| Test method | Description |
|-------------|-------------|
| `getSdmxBeans_appliesFixturesAfterFetch()` | `applyFixtures` receives the fetcher's bytes; the parser receives the fixture output |
| `getStructures_conversionUsesFetcher_neverAdapter()` | `genericRegistryAdapter.getStructures` is never called on the conversion and bypass paths |
| `getStructures_conversion_capturesUsagesFromRegistryBytes()` | With `PRESERVE_METADATA_ATTRIBUTE_USAGES`, `capture` receives the fetcher's bytes, not fixture output |
| `getStructures_conversion_fetchTimeoutPropagatesUnwrapped()` | `RegistryResponseTimeoutException` from the fetcher escapes the `StreamingResponseBody` as itself |
| `getStructures_conversion_emptyFetch_writesNothing()` | Empty array: nothing written, no ready-response put |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsageFolderTest.java`

Replace the `GenericRegistryAdapter` mock with a `RawStructureFetcher` mock returning bytes. The
existing assertions stay as they are.

### Manual verification

Start the proxy with an empty cache and the OECD configuration, then send 20 identical requests at
once:

```bash
./gradlew :sdmx-proxy:bootRun
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  "http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/OECD.ENV.EPI/DSD_AIR_GHG/+"
```

Expected: 20 responses with status `200`, one `Single-flight: calling registry` log line, and 19
`Single-flight: joined in-flight call` lines. Repeat the request with
`Accept: application/vnd.sdmx.structure+xml;version=2.1`: no new `calling registry` line.

### E2E tests

Run the OECD suite through the `e2e-report` skill. It is the registry most sensitive to request
count, so the 429 count in the report should drop compared to a run on the parent commit. Run BIS
and IMF as regression checks for the 3.0 path and the metadata-attribute-usage fixtures.

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.OECD_2_1_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
```

No E2E config changes are needed.

## Deferred to the distributed version

- **Cross-instance coalescing.** `InProcessRequestCoalescer` coalesces within one JVM. A Redis
  implementation of `RequestCoalescer` needs a lock with a lease (`SET key NX PX`), lease renewal
  for calls that outlive it, and a way for other instances to learn the result: polling the raw
  cache or pub/sub. The in-flight re-check in `RawStructureFetcherImpl.load` already reads the
  shared cache, so it carries over.
- **Shared cooldown.** The cooldown cache is a per-process Caffeine instance. With several
  instances behind one egress IP, it belongs in Redis next to the lock.
- **Per-registry cooldown and pacing.** Cooling down a whole registry, or pacing requests below its
  limit, protects quota across distinct keys. This design only removes duplicate calls.
- **Stale-while-error.** Serving an expired raw entry when the registry fails needs a stale tier in
  `CacheService`.
- **Data and availability passthrough.** Streamed responses would have to be buffered to be
  shared.
- **Memoizing post-fixture bytes.** If the extra fixture pass in `getSdmxBeans` shows up in
  profiles, cache its output in-process.

## Review log
