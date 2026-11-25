# Design: Data-fixture subsystem and BIS monthly TIME_PERIOD normalization

## Context

StatGPT relies on `TIME_PERIOD` values being emitted in the canonical SDMX-JSON 2.0.0
`ReportingMonthType` / `ObservationalTimePeriod` form `YYYY-Mmm` (e.g. `2024-M03`) to identify
monthly data. IMF already returns values in this form. BIS does not -- it emits `YYYY-MM`
(e.g. `2024-03`) with no `M` marker, so downstream components cannot reliably distinguish
monthly data from other periodicities and BIS / IMF renderings diverge. Issue #42.

This change introduces two linked pieces:

1. A general **data-fixture subsystem** -- parallel to the existing structure and availability
   fixture subsystems, but with a hard streaming contract. Data responses can be arbitrarily
   large and are delivered to clients as streaming bodies; a data fixture must never
   materialize the full response.
2. The **first data fixture** -- `TIME_PERIOD_MONTHLY_NORMALIZATION` for `JSON_1_0_0` -- and
   enabling it on the BIS SDMX 3.0 data endpoint.

Both pieces are shipped together because the subsystem is what makes the fix possible and the
BIS fix proves the subsystem.

## Problem

BIS `/api/v2/data/` returns SDMX-JSON 1.0.0 with `defaultFormat: JSON_1_0_0` and
`bypassEnabled: false`, so every data response flows through the proxy's conversion path
(`AdapterRouterImpl.getData()` lines 183-201). Inside the response, `TIME_PERIOD` appears as an
observation dimension:

```json
{
  "data": {
    "structures": [
      {
        "dimensions": {
          "observation": [
            {
              "id": "TIME_PERIOD",
              "keyPosition": 3,
              "values": [
                { "value": "2024-03" },
                { "value": "2024-04" },
                { "value": "2024-05" }
              ]
            }
          ]
        }
      }
    ]
  }
}
```

IMF emits `2024-M03`, `2024-M04`, ... in the same position. Both are valid strings for
`ObservationalTimePeriod`'s `xs:string` base, but only the `YYYY-Mmm` form is the canonical
`ReportingMonthType` literal defined by SDMX-JSON 2.0.0 and used by StatGPT to route monthly
data.

The proxy currently cannot patch data responses: structure and availability endpoints have a
fixture subsystem (`services/fixture/structure/`, `services/fixture/availability/`) but the
data endpoint has no equivalent hook between `genericRegistryAdapter.getData(query)` and
`streamingDataConversionService.convert(...)`. `DataEndpointConfiguration` has no `fixtures`
list.

Normalizing the raw BIS stream before parsing means the sdmx-core reader
(`CustomSdmxJsonDataReaderEngineV2`) sees canonical values, so every client output format
(SDMX-JSON 2.0.0, SDMX-ML 3.0, SDMX-CSV 2.0.0) inherits the fix without per-writer work.

## Non-goals

- Quarterly, weekly, daily, yearly normalization. Scope is monthly only, matching issue #42.
- Bypass-path responses. Fixtures run only on the conversion path, matching the established
  behavior of structure and availability fixtures. BIS data has `bypassEnabled: false`, so
  this is moot for the reported case. A future registry that enables bypass and needs the
  same fix would be a separate design.
- FREQ-aware detection. The fixture is purely pattern-based on the `TIME_PERIOD` value, which
  avoids correlating series keys to FREQ codes and is sufficient for the values BIS emits.
- Non-JSON registry formats for this particular fixture. BIS returns `JSON_1_0_0`. The
  subsystem is format-agnostic -- a CSV or SDMX-ML implementation of the same
  `DataFixtureType` can be added later without touching the subsystem.

## Solution overview

Introduce a `DataFixture` subsystem parallel to `AvailabilityFixture`, with one critical
departure: **the interface contract mandates streaming**. Implementations must process the
input as a stream and return an `InputStream` that produces transformed bytes on demand.
Buffering the full response (e.g. `readTree`, `readAllBytes`, `TokenBuffer`) is forbidden.

Hook the subsystem into `AdapterRouterImpl.getData()` on the conversion branch, between the
registry call and the conversion service.

Ship one implementation -- `TimePeriodMonthlyNormalizationJsonDataFixture` for `JSON_1_0_0` --
that rewrites monthly `TIME_PERIOD` values via Jackson's streaming `JsonParser` / `JsonGenerator`
connected through a `PipedInputStream` / `PipedOutputStream` driven by a virtual thread.

Enable the fixture for BIS 3.0 in both the primary and E2E registry configs.

## Architecture

### 1. Config module pieces (`sdmx-proxy-config/`)

#### 1.1 `DataFixtureType` enum (new)

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/DataFixtureType.java`

```java
package com.epam.sdmxproxy.configuration.data.fixture;

public enum DataFixtureType implements FixtureType {
    TIME_PERIOD_MONTHLY_NORMALIZATION
}
```

#### 1.2 `DataEndpointConfiguration.fixtures` (modified)

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/DataEndpointConfiguration.java`

Add a `fixtures` field (mirroring `AvailabilityEndpointConfiguration`) and the necessary
imports.

```java
package com.epam.sdmxproxy.configuration.data;

import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataEndpointConfiguration extends EndpointConfiguration {

    /**
     * When true, empty dimensions in the data query key are replaced with '*'.
     * Required for registries (e.g., IMF) that treat an empty dimension
     * differently from the wildcard '*' in data queries.
     * For example, ".L_T.P_F3" becomes "*.L_T.P_F3".
     */
    private boolean replaceEmptyDimensionsWithWildcard;

    /**
     * When true, collapse a key whose every position is '*' to a single '*'
     * (e.g. '*.*.*.*' becomes '*'). Required for BIS and similar registries.
     */
    private boolean mergeAllWildcardKey;

    /**
     * List of fixtures to apply to the raw registry data response before conversion.
     * Each fixture patches a specific known issue (e.g., non-canonical TIME_PERIOD values).
     * Applied as a chain of responsibility in the order they are listed.
     * Data fixtures must be streaming -- see {@code DataFixture}.
     */
    private List<FixtureConfiguration<DataFixtureType>> fixtures;
}
```

### 2. Main-module pieces (`sdmx-proxy/`)

#### 2.1 `DataFixture` interface (new)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/data/DataFixture.java`

```java
package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Base interface for data-endpoint fixtures.
 * <p>
 * Implementations MUST process the input as a stream and return an {@link InputStream} that
 * produces transformed bytes on demand. Buffering the entire response (e.g. via
 * {@code ObjectMapper.readTree}, {@code InputStream.readAllBytes}, Jackson's
 * {@code TokenBuffer}, or equivalent) is forbidden: data responses may be arbitrarily large
 * and are returned to clients as a streaming body. Peak additional memory per request should
 * be bounded by a small, fixed buffer independent of response size.
 */
public interface DataFixture {

    /** Identifier used to match against {@code FixtureConfiguration.getType()}. */
    DataFixtureType getType();

    /** Registry return formats this implementation can transform. */
    Set<ReturnFormat> supportedFormats();

    /**
     * Wraps {@code input} with this fixture's streaming transformation.
     *
     * @param input     raw registry response stream
     * @param sdmxBeans DSD context for fixtures that need component metadata; may be
     *                  {@code null} if the caller could not resolve it
     * @param config    free-form key/value configuration for this fixture instance
     * @return an {@link InputStream} that yields the transformed bytes
     */
    InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config);
}
```

#### 2.2 `DataFixtureService` (new)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/data/DataFixtureService.java`

```java
package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * Applies data-endpoint fixtures as a streaming chain of responsibility.
 * <p>
 * The service itself holds no bytes: each call to {@link DataFixture#apply} returns a wrapped
 * {@link InputStream}, and this service passes that wrapped stream into the next fixture in
 * the chain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFixtureService {

    private final List<DataFixture> fixtures;

    public InputStream applyFixtures(
            InputStream input,
            ReturnFormat format,
            SdmxBeans sdmxBeans,
            List<FixtureConfiguration<DataFixtureType>> fixtureConfigs
    ) {
        if (fixtureConfigs == null || fixtureConfigs.isEmpty()) {
            return input;
        }

        InputStream current = input;
        for (FixtureConfiguration<DataFixtureType> fc : fixtureConfigs) {
            DataFixture fixture = findFixture(fc.getType(), format);
            if (fixture != null) {
                log.debug("Applying data fixture {} for format {}", fc.getType(), format);
                current = fixture.apply(current, sdmxBeans, fc.getConfig());
            } else {
                log.debug("No data fixture implementation for type {} and format {}, skipping", fc.getType(), format);
            }
        }
        return current;
    }

    private DataFixture findFixture(DataFixtureType type, ReturnFormat format) {
        return fixtures.stream()
                .filter(f -> f.getType() == type && f.supportedFormats().contains(format))
                .findFirst()
                .orElse(null);
    }
}
```

#### 2.3 `TimePeriodMonthlyNormalizationJsonDataFixture` (new)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/data/TimePeriodMonthlyNormalizationJsonDataFixture.java`

The fixture uses Jackson's streaming API end-to-end: `JsonParser` for input, `JsonGenerator`
for output, connected through a piped stream pair. A virtual thread drives the transform so
the consumer (the conversion service) and producer (the transform) run concurrently with
natural backpressure from the pipe.

**State-machine approach** (the simpler of the two alternatives considered):

- The transform mirrors every parser token to the generator unchanged, with one exception.
- A small state tracks whether we are currently inside an observation-dimension object whose
  `id` field equals `"TIME_PERIOD"`. When true, the fixture watches for the enclosed
  `values[*]` array, and for each `value` string field inside it applies the regex
  `^(\\d{4})-(\\d{2})$` -> `$1-M$2`. Non-matching strings pass through unchanged.
- Only `.../dimensions/observation/...` is targeted. Series-positioned `TIME_PERIOD` (which
  occurs when the client supplies `dimensionAtObservation` != `TIME_PERIOD`) is not rewritten
  in this version; the proxy's default is TIME_PERIOD-as-observation, so this covers the
  reported case.

```java
package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes monthly {@code TIME_PERIOD} values in SDMX-JSON 1.0.0 data responses to the
 * canonical {@code YYYY-Mmm} form (e.g. {@code 2024-03} becomes {@code 2024-M03}).
 * <p>
 * Implementation notes: streaming end-to-end via Jackson's {@link JsonParser} /
 * {@link JsonGenerator}, wired through a {@link PipedInputStream} / {@link PipedOutputStream}
 * on a virtual thread. Peak memory per request is bounded by the pipe buffer plus Jackson's
 * internal block buffers, independent of response size.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimePeriodMonthlyNormalizationJsonDataFixture implements DataFixture {

    private static final Pattern MONTHLY = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final int PIPE_BUFFER_BYTES = 64 * 1024;
    private static final String TIME_PERIOD = "TIME_PERIOD";
    private static final String ID = "id";
    private static final String VALUES = "values";
    private static final String VALUE = "value";

    private final ObjectMapper objectMapper;

    @Override
    public DataFixtureType getType() {
        return DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.JSON_1_0_0);
    }

    @Override
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        JsonFactory factory = objectMapper.getFactory();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        PipedInputStream pis;
        PipedOutputStream pos;
        try {
            pos = new PipedOutputStream();
            pis = new PipedInputStream(pos, PIPE_BUFFER_BYTES);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to set up piped streams for data fixture", e);
        }

        Thread.ofVirtual().name("data-fixture-monthly-time-period").start(() -> {
            try (InputStream in = input;
                 JsonParser parser = factory.createParser(in);
                 JsonGenerator gen = factory.createGenerator(pos)) {
                transform(parser, gen);
            } catch (Throwable t) {
                failure.set(t);
                log.error("TIME_PERIOD monthly normalization fixture failed", t);
            }
        });

        return new FailurePropagatingInputStream(pis, failure);
    }

    private void transform(JsonParser parser, JsonGenerator gen) throws IOException {
        boolean inTimePeriodDim = false;
        int dimDepth = -1;
        boolean inValuesArray = false;
        int valuesDepth = -1;

        JsonToken token = parser.nextToken();
        while (token != null) {
            switch (token) {
                case START_OBJECT -> gen.writeStartObject();
                case END_OBJECT -> {
                    gen.writeEndObject();
                    if (inTimePeriodDim && parser.getParsingContext().getNestingDepth() <= dimDepth) {
                        inTimePeriodDim = false;
                        dimDepth = -1;
                    }
                }
                case START_ARRAY -> {
                    if (inTimePeriodDim && VALUES.equals(parser.getCurrentName()) && !inValuesArray) {
                        inValuesArray = true;
                        valuesDepth = parser.getParsingContext().getNestingDepth();
                    }
                    gen.writeStartArray();
                }
                case END_ARRAY -> {
                    gen.writeEndArray();
                    if (inValuesArray && parser.getParsingContext().getNestingDepth() < valuesDepth) {
                        inValuesArray = false;
                        valuesDepth = -1;
                    }
                }
                case FIELD_NAME -> gen.writeFieldName(parser.getCurrentName());
                case VALUE_STRING -> {
                    String text = parser.getText();
                    if (!inTimePeriodDim && ID.equals(parser.getCurrentName()) && TIME_PERIOD.equals(text)) {
                        inTimePeriodDim = true;
                        dimDepth = parser.getParsingContext().getNestingDepth();
                    }
                    if (inValuesArray && VALUE.equals(parser.getCurrentName())) {
                        gen.writeString(normalize(text));
                    } else {
                        gen.writeString(text);
                    }
                }
                case VALUE_NUMBER_INT -> gen.writeNumber(parser.getLongValue());
                case VALUE_NUMBER_FLOAT -> gen.writeNumber(parser.getDoubleValue());
                case VALUE_TRUE -> gen.writeBoolean(true);
                case VALUE_FALSE -> gen.writeBoolean(false);
                case VALUE_NULL -> gen.writeNull();
                case VALUE_EMBEDDED_OBJECT -> gen.writeObject(parser.getEmbeddedObject());
                default -> {
                    // NOT_AVAILABLE should not appear in a blocking parser
                }
            }
            token = parser.nextToken();
        }
    }

    private String normalize(String value) {
        Matcher matcher = MONTHLY.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(1) + "-M" + matcher.group(2);
    }

    /**
     * Wraps the consumer-side pipe so that a producer-thread failure surfaces as
     * {@link IOException} on the next {@code read()}.
     */
    private static final class FailurePropagatingInputStream extends FilterInputStream {
        private final AtomicReference<Throwable> failure;

        private FailurePropagatingInputStream(InputStream delegate, AtomicReference<Throwable> failure) {
            super(delegate);
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                checkFailure();
                return b;
            } catch (IOException e) {
                checkFailure();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = super.read(b, off, len);
                checkFailure();
                return n;
            } catch (IOException e) {
                checkFailure();
                throw e;
            }
        }

        private void checkFailure() throws IOException {
            Throwable t = failure.get();
            if (t != null) {
                throw new IOException("Data fixture producer failed", t);
            }
        }
    }
}
```

Notes on the state machine:

- `getParsingContext().getNestingDepth()` returns the current parser-side depth. Capturing it
  at `START_OBJECT` (when `id == TIME_PERIOD` is seen on the next `VALUE_STRING`) lets the
  `END_OBJECT` handler detect when we leave the `TIME_PERIOD` dimension object.
- The fixture never copies whole subtrees with `copyCurrentStructure` -- each token is written
  individually so we retain token-level control over value rewrites. This is still streaming:
  Jackson's parser and generator both operate on fixed-size internal buffers.
- `VALUE_EMBEDDED_OBJECT` is included for completeness; SDMX-JSON does not emit it, but
  omitting the branch would swallow an unexpected token type silently.

### 3. Hook in `AdapterRouterImpl`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

Inject `DataFixtureService` via the existing `@RequiredArgsConstructor`. On the conversion
branch of `getData()` (currently lines 183-201), resolve the DSD once, wrap the raw stream
through the fixture service, then pass the wrapped stream to
`streamingDataConversionService.convert(...)`.

Current conversion branch:

```java
return outputStream -> {
    try (InputStream inputStream = genericRegistryAdapter.getData(query)) {
        SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));

        streamingDataConversionService.convert(
                inputStream,
                outputStream,
                sdmxBeans,
                returnFormat,
                requestedMediaType
        );
    } catch (FeignException e) {
        log.warn("Failure on registry side.", e);
        throw e;
    } catch (Exception e) {
        log.error("Failed to convert data from {} to {}", returnFormat, requestedMediaType, e);
        throw new IllegalArgumentException("Failed to convert data", e);
    }
};
```

Replacement (bypass branch unchanged):

```java
return outputStream -> {
    DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
    SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));
    try (InputStream raw = genericRegistryAdapter.getData(query);
         InputStream inputStream = dataFixtureService.applyFixtures(
                 raw,
                 returnFormat,
                 sdmxBeans,
                 dataConfig != null ? dataConfig.getFixtures() : null
         )) {

        streamingDataConversionService.convert(
                inputStream,
                outputStream,
                sdmxBeans,
                returnFormat,
                requestedMediaType
        );
    } catch (FeignException e) {
        log.warn("Failure on registry side.", e);
        throw e;
    } catch (Exception e) {
        log.error("Failed to convert data from {} to {}", returnFormat, requestedMediaType, e);
        throw new IllegalArgumentException("Failed to convert data", e);
    }
};
```

`applyFixtures` short-circuits when `fixtures` is null or empty, returning the raw stream
unchanged -- so registries with no configured data fixtures pay zero overhead. The
try-with-resources on the fixture-wrapped stream closes the raw stream on exit via the
`FilterInputStream` -> `PipedInputStream` delegate chain; the producer thread closes the
generator / piped output in its own try-with-resources.

**New imports:**

```java
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.services.fixture.data.DataFixtureService;
```

Field addition (added to the Lombok-generated constructor automatically):

```java
private final DataFixtureService dataFixtureService;
```

### 4. BIS registry config (primary)

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`

In the BIS `SDMX_3_0.dataEndpointConfig` block, add `fixtures`:

```json
"dataEndpointConfig": {
  "url": "https://stats.bis.org/api/v2/data/",
  "supportedFormats": [
    "JSON_1_0_0",
    "CSV_DATA_2_0_0"
  ],
  "defaultFormat": "JSON_1_0_0",
  "bypassEnabled": false,
  "mergeAllWildcardKey": true,
  "fixtures": [
    {
      "type": "TIME_PERIOD_MONTHLY_NORMALIZATION",
      "config": {}
    }
  ]
}
```

### 5. BIS registry config (E2E)

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_registry_config.json`

Mirror the same `fixtures` entry in the E2E config so the existing BIS parameterized data
tests exercise the fixture end-to-end.

### 6. README update

**File:** `sdmx-proxy-config/README.md`

Per the project rule on keeping the README schema tables in sync with the config classes:

- Extend the `DataEndpointConfiguration` table with a new row:

  | Field      | Required | Description                                                                                                         | Available Values                                | Default |
  |------------|:--------:|---------------------------------------------------------------------------------------------------------------------|-------------------------------------------------|---------|
  | `fixtures` | No       | Response patches applied as a streaming chain to the raw registry response before conversion, in the order listed   | Array of `FixtureConfiguration<DataFixtureType>`| (empty) |

- Add a `DataFixtureType` sub-table alongside the existing `StructureFixtureType` and
  `AvailabilityFixtureType` tables:

  | Value                               | Description                                                                                                       |
  |-------------------------------------|-------------------------------------------------------------------------------------------------------------------|
  | `TIME_PERIOD_MONTHLY_NORMALIZATION` | Rewrite monthly `TIME_PERIOD` values to canonical `YYYY-Mmm` (e.g. `2024-03` to `2024-M03`). `JSON_1_0_0` only in v1. |

## Edge cases

- **Already-canonical values** (`2024-M03`): regex does not match; passed through unchanged.
  Covered by unit test.
- **Annual / quarterly / daily periods** (`2024`, `2024-Q1`, `2024-03-15`): regex anchored
  with `^...$` so none match; passed through unchanged.
- **TIME_PERIOD as a series dimension** (when the client requests
  `dimensionAtObservation != TIME_PERIOD`): the state machine only inspects
  `dimensions.observation[*]`. BIS / the proxy default is TIME_PERIOD-as-observation, so this
  is the reported case. Extending the state machine to also inspect `dimensions.series[*]` is
  straightforward if the case appears in practice.
- **Bypass path**: untouched. If a registry enables bypass for a format where the fixture
  would otherwise apply, the fixture does not run. BIS has bypass disabled on data, so this
  does not affect the reported case.
- **Non-`JSON_1_0_0` registry formats**: `DataFixtureService.findFixture()` returns null when
  no implementation's `supportedFormats()` contains the active format, and the chain skips
  (debug log). Configuring `TIME_PERIOD_MONTHLY_NORMALIZATION` on a registry that returns, for
  example, CSV is a no-op until a CSV implementation is added. This is the intended
  extensibility path for "fixtures for all registry formats".
- **Producer thread failure**: surfaced to the consumer as `IOException` via
  `FailurePropagatingInputStream`. `AdapterRouterImpl`'s existing catch block converts the
  `IOException` into an `IllegalArgumentException` -- the same behavior as a conversion
  failure today.
- **Caching**: `AdapterRouterImpl.getData()` does not cache. No cache interaction.
- **`sdmxBeans` unused by this fixture**: the fixture signature accepts `sdmxBeans` for
  subsystem symmetry (availability fixtures rely on it for DSD-driven logic) but the monthly
  normalization fixture ignores it -- the transform is pattern-based.
- **Virtual-thread creation cost**: one virtual thread per data request with at least one
  fixture configured. Virtual threads are cheap (no OS thread pinned); this is acceptable.
- **Registries with empty or null `fixtures` list**: `applyFixtures` short-circuits and
  returns the raw stream. IMF, Eurostat, and any other currently configured registry see no
  change.

## Verification

1. Build: `./gradlew clean build -x test`.
2. Unit tests: `./gradlew :sdmx-proxy:test`.
3. Manual smoke test:
   - Run the proxy: `./gradlew :sdmx-proxy:bootRun`.
   - Request a BIS monthly slice in SDMX-JSON 2.0.0:
     ```
     GET /api/v0/sdmx/3.0/data/dataflow/BIS/WS_CBPOL/1.0/M.US
     Accept: application/vnd.sdmx.data+json; version=2.0.0
     ```
   - Assert that every `TIME_PERIOD` value in the response matches `^\d{4}-M\d{2}$`.
   - Repeat for SDMX-ML 3.0 and SDMX-CSV 2.0.0 output; the normalized values should flow
     through the conversion pipeline unchanged.
4. E2E: `./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"` -- the
   existing BIS data parameterized tests (which already exercise `WS_CBPOL M.US.*` and
   `WS_CBTA M.US.*.*`) must continue to pass with the fixture enabled.

### Unit test plan

**File (new):** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/data/TimePeriodMonthlyNormalizationJsonDataFixtureTest.java`

- `apply_rewritesMonthlyTimePeriodValues` -- input has TIME_PERIOD observation dimension with
  `2024-03`, `2024-04`; output has `2024-M03`, `2024-M04`.
- `apply_leavesCanonicalValuesUnchanged` -- input `2024-M03`; output unchanged.
- `apply_ignoresAnnualValues` -- input `2024`; output unchanged.
- `apply_ignoresDailyValues` -- input `2024-03-15`; output unchanged.
- `apply_ignoresNonTimePeriodDimensions` -- input has a FREQ dimension with a `values` entry
  `{"value":"2024-03"}` (pathological); output unchanged.
- `apply_preservesOtherJsonStructure` -- compare input vs output tree equality outside the
  transformed values.
- `apply_streamingDoesNotBufferFullInput` -- wrap the input with a `TrackingInputStream` that
  records cumulative bytes read so far at each call; assert the consumer begins receiving
  output after the producer has read far less than the full input (concrete threshold:
  2 * `PIPE_BUFFER_BYTES`).
- `apply_propagatesProducerFailure` -- feed malformed JSON; assert `IOException` from
  consumer `read()` whose `getCause()` is the Jackson parse exception.

**File (new):** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/data/DataFixtureServiceTest.java`

- `applyFixtures_noConfiguredFixtures_returnsInputUnchanged`.
- `applyFixtures_nullFixtureList_returnsInputUnchanged`.
- `applyFixtures_chainsMultipleFixturesInOrder` -- two mock fixtures, verify the chain order
  via Mockito `InOrder`.
- `applyFixtures_skipsWhenNoImplementationMatchesFormat` -- fixture typed for `JSON_1_0_0`,
  active format is `XML_GENERICDATA_2_1` -- stream returned unchanged, debug log present.

**File (modified):** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImplTest.java`

Extend the data-path tests:

- `getData_withConfiguredFixtures_invokesDataFixtureService`.
- `getData_withoutConfiguredFixtures_doesNotInvokeDataFixtureService`.
- `getData_bypassPath_doesNotInvokeDataFixtureService`.

## Files affected

| File                                                                                                         | Change   | Purpose                                                      |
|--------------------------------------------------------------------------------------------------------------|----------|--------------------------------------------------------------|
| `sdmx-proxy-config/.../configuration/data/fixture/DataFixtureType.java`                                      | New      | Enum with `TIME_PERIOD_MONTHLY_NORMALIZATION`                |
| `sdmx-proxy-config/.../configuration/data/DataEndpointConfiguration.java`                                    | Modified | Add `fixtures` list field                                    |
| `sdmx-proxy-config/README.md`                                                                                | Modified | Add `fixtures` row + `DataFixtureType` sub-table             |
| `sdmx-proxy/.../services/fixture/data/DataFixture.java`                                                      | New      | Interface with streaming contract                            |
| `sdmx-proxy/.../services/fixture/data/DataFixtureService.java`                                               | New      | Streaming chain orchestrator                                 |
| `sdmx-proxy/.../services/fixture/data/TimePeriodMonthlyNormalizationJsonDataFixture.java`                    | New      | Streaming Jackson transform for `JSON_1_0_0`                 |
| `sdmx-proxy/.../services/adapter/AdapterRouterImpl.java`                                                     | Modified | Inject `DataFixtureService`; wrap raw data stream            |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`                                           | Modified | Enable fixture for BIS 3.0 data                              |
| `sdmx-proxy-e2e/.../bis/3_0/bis_3_0_registry_config.json`                                                    | Modified | Mirror BIS config in E2E                                     |
| `sdmx-proxy/.../services/fixture/data/TimePeriodMonthlyNormalizationJsonDataFixtureTest.java`                | New      | Unit tests for the fixture                                   |
| `sdmx-proxy/.../services/fixture/data/DataFixtureServiceTest.java`                                           | New      | Unit tests for the orchestrator                              |
| `sdmx-proxy/.../services/adapter/AdapterRouterImplTest.java`                                                 | Modified | Hook verification                                            |

## No changes required

- `QueryTranslatorImpl` -- fixture dispatch is orthogonal to routing.
- `GenericRegistryAdapterImpl` -- continues to return the raw registry stream; transformation
  happens above it.
- `CustomSdmxJsonDataReaderEngineV2` and other conversion engines -- the stream is normalized
  before they see it.
- IMF, Eurostat, and other registry configs -- leaving `dataEndpointConfig.fixtures` unset
  keeps current behavior (no fixtures applied).
- `StreamingDataConversionService` -- unchanged; it still parses the stream handed to it.
