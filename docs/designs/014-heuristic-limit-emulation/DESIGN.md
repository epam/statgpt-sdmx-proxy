# Design: Heuristic `limit` emulation for registries that do not support it (Issue #40)

## Context

The SDMX 3.0 REST specification defines a `limit` query parameter on the `/data` endpoint
that caps the number of series returned by the registry. The BIS SDMX v2 registry
(`https://stats.bis.org/api/v2`) does not accept `limit` -- see the parameter set at
https://stats.bis.org/api-doc/v2/#/Data%20queries/get_data__context___agencyID___resourceID___version___key_.
BIS does offer `firstNObservations` / `lastNObservations`, but those cap observations per
series, not the total number of series, and therefore cannot substitute for `limit`.

Without emulation, a client-supplied `limit` on a BIS-routed request is silently ignored.
For a broad key (e.g. `*`) BIS may respond with hundreds of megabytes of series. The proxy
does not cache data responses (structures are cached, data is not), so a handful of
concurrent broad requests is enough to exhaust heap. This is the concrete denial-of-service
risk the change addresses.

The emulation leverages the registry's `/availability` endpoint -- which BIS does support
and which by design is much cheaper than `/data` -- to shrink the client filter to a
rectangular sub-region of the cube whose size estimate is close to `N`. A single `/data`
request is then issued with the shrunk filter; the response is streamed through a
per-format truncator that cuts at exactly `N` series.

Both SDMX 3.0 and SDMX 2.1 are supported. 3.0 narrows via `c[]` filters; 2.1 narrows via
the positional key (no `c[]` in 2.1).

## Notation

The shrink loop and surrounding code pass several symbols around. A short reference
(see `017-bisect-proportional-restep/ALGORITHM_GUIDE.md` in this repo for fuller
definitions and worked examples):

| Symbol           | Meaning                                                                                                                                                                                                 |
|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `N`              | The client's `limit` parameter -- the requested cap on the number of returned series.                                                                                                                   |
| `tolerance`      | Per-registry overshoot factor (`limitEmulationTolerance`, default `1.2`).                                                                                                                               |
| `target`         | `floor(N * tolerance)` -- upper edge of the band of acceptable registry-side series counts.                                                                                                             |
| `series_count`   | Annotation on the data constraint in the availability response: the registry's count of series in the filtered cube.                                                                                    |
| `M`              | The `effectiveSeriesCount` of the projection from the most recent probe -- equal to `series_count` when present, else the combinatorial upper bound.                                                    |
| `A_d`            | Per-dim list of values that the registry reports for dim `d` in the availability response.                                                                                                              |
| `\|A_d\|`        | Size of `A_d` (also called `dimSize` in code).                                                                                                                                                          |
| `k`              | Number of values to keep on the chosen dim. Integer in `[1, \|A_d\|]`.                                                                                                                                  |
| `kLow` / `kHigh` | Best known undershoot / overshoot bounds on `k` during the bisect. The "right" `k` lies in `[kLow, kHigh]`. (Introduced in design 017's bisect refactor; not used in the original proportional shrink.) |
| `budget`         | `limitEmulationProbeBudget` -- maximum number of `/availability` probes the algorithm may issue per request.                                                                                            |

## Non-goals

- **Exact strict `N` when the cube is sparse.** The shrink loop uses
  `AvailabilityProjection.effectiveSeriesCount()` -- the registry's `series_count`
  annotation when present, otherwise the combinatorial upper bound `prod |A_d|` -- as a
  proxy for the actual filtered series count. If the registry's actual cube is
  significantly sparser than the projection suggests, the response will contain fewer than
  `N` series. The truncator emits everything it received; the proxy logs nothing extra.
  Persistent undershoot is treated as a registry-specific bug to be filed and fixed in a
  follow-up.
- **Merging multiple data responses into one.** V1 issues exactly one `/data` request. If
  the shrunk filter undershoots, V1 does not issue additional data requests to top up.
- **Parallel `availability` probes of alternative shrink strategies.** The algorithm is
  sequential (one candidate per iteration). Parallel exploration is an optimization, not a
  correctness requirement.
- **Caching the availability probes.** The availability responses produced during
  emulation are not cached. Caching is a future optimization.
- **Bypass path.** Bypass for data is already off for BIS (`bypassEnabled: false`); the
  emulation runs only on the conversion path. If a future registry has
  `supportsLimit: false` *and* `bypassEnabled: true`, bypass wins and emulation is
  skipped.
- **Telemetry.** Micrometer-based metrics were considered but dropped. The system relies
  on log-based observability: every probe, shrink iteration, and final shrunk query is
  logged at INFO; cap-reached and empty-projection events are WARN. Metrics may be
  added later if log-based monitoring proves insufficient.

## Solution overview

Five pieces:

1. **Per-registry flag** `supportsLimit: boolean` on `DataEndpointConfiguration`, default
   `true`. Two tuning knobs alongside it: `limitEmulationTolerance` (default `1.2`) and
   `limitEmulationMaxShrinkIterations` (default `32`). Set `supportsLimit: false` for
   registries that ignore the parameter (BIS).
2. **Streaming availability response parser** that converts the raw SDMX-JSON structure
   availability response to an `AvailabilityProjection` (per-dim values + optional
   `series_count` annotation). The parser handles both `cubeRegions[*].components[*]`
   (IMF) and `cubeRegions[*].keyValues[*]` (BIS FusionRegistry) shapes.
3. **`LimitEmulationService.getShrunkQuery(query, sdmxBeans, prober)`** -- runs the
   greedy shrink loop and returns a modified `TranslatedDataQuery` with `.limit(null)`.
   Branches on SDMX version: 3.0 narrows `c[]` filters; 2.1 narrows the positional key
   (via `KeyParser`). Probes the registry through an `AvailabilityProber` callback so the
   service has no direct dependency on `GenericRegistryAdapter`.
4. **Per-format `SeriesLimitTruncator`** wraps the registry's data stream and emits at
   most `N` distinct series. One implementation per format; `SeriesLimitTruncatorProvider`
   resolves by `ReturnFormat`.
5. **`AdapterRouterImpl` orchestration**. When `supportsLimit == false` and a `limit` is
   present, the router calls `getShrunkQuery`, then `genericRegistryAdapter.getData(
   shrunkQuery)`, then `truncator.truncate(raw, N, beans)` -- in that order. Architectural
   invariant: only `AdapterRouter` calls `GenericRegistryAdapter`.

See `architecture.puml` for the component picture and `sequence.puml` for the request
flow.

## Algorithm

See `algorithm.puml` for the flowchart.

### Inputs

- `query`: client `TranslatedDataQuery`.
- `N`: client-supplied `limit`.
- `tolerance`: configurable overshoot factor on the registry-side target (default `1.2`).
- `target = floor(N * tolerance)`.

### Pseudocode

```
function getShrunkQuery(query, sdmxBeans, prober):
    target = floor(query.limit * tolerance)
    if query.sdmxVersion == SDMX_2_1:
        return runShrinkLoop21(query, sdmxBeans, target, prober)
    return runShrinkLoop30(query, sdmxBeans, target, prober)

function runShrinkLoop30(query, sdmxBeans, target, prober):
    filters = deepCopy(query.filters)
    A = probe30(query, filters, sdmxBeans, prober)
    M = A.effectiveSeriesCount
    iter = 0
    while M > target and iter < MAX_SHRINK_ITERATIONS:
        decision = FilterShrinker.decide(A, target, timeDimId)
        if decision.isNone(): break
        filters[decision.dimensionId] = decision.retainedValues
        A = probe30(query, filters, sdmxBeans, prober)
        M = A.effectiveSeriesCount
        iter += 1
    return query.toBuilder.filters(filters).limit(null).build

function runShrinkLoop21(query, sdmxBeans, target, prober):
    nonTimeDims = DSD non-time dim ids in order
    state = KeyParser.parseKey(query.key, nonTimeDims)
    A = probe21(query, sdmxBeans, state, nonTimeDims, prober)
    M = A.effectiveSeriesCount
    iter = 0
    while M > target and iter < MAX_SHRINK_ITERATIONS:
        decision = FilterShrinker.decide(A, target, timeDimId)
        if decision.isNone(): break
        state[decision.dimensionId] = decision.retainedValues
        A = probe21(query, sdmxBeans, state, nonTimeDims, prober)
        M = A.effectiveSeriesCount
        iter += 1
    newKey = KeyParser.buildKey(state, nonTimeDims, mergeAllWildcard)
    return query.toBuilder.key(newKey).filters(null).limit(null).build
```

`FilterShrinker.decide` internally:

```
function decide(A, target, timeDimId):
    candidates = dims in A where size(A_d) > 1 and d != timeDimId
    if candidates empty: return none()
    d = argmax(size(A_d)) in candidates  // ties: registry order
    k = max(1, ceil(target * size(A_d) / M))
    if k >= size(A_d): return none()
    return ShrinkDecision(d, first k values of A_d)
```

### Why `ceil` for `k`

`k = ceil(target * |A_d| / M)` guarantees the new projection upper bound `M' = (k /
|A_d|) * M >= target >= N`. `floor` would produce `M' <= target`, which can land *below*
`N` -- undershooting in a single iteration. Since undershoot is the failure mode the
algorithm tries to avoid, `ceil` is correct even when it leaves `M` above the target
after one iteration. The loop simply runs again -- typically 1-3 iterations in practice
for dataflows of 3-10 dimensions. Worst case is bounded by `MAX_SHRINK_ITERATIONS`.

### Effective series count

`AvailabilityProjection.effectiveSeriesCount()`:

- Returns `seriesCount` (the `series_count` annotation on the data constraint) when
  present. BIS's FusionRegistry emits this on every availability response with mode
  `exact`, and it is the *exact* count of series in the filtered cube.
- Falls back to the combinatorial upper bound `prod |A_d|` (with `Long.MAX_VALUE`
  saturation on overflow) when the annotation is absent. This is conservative -- the
  loop may shrink more aggressively than strictly needed, but never undershoot due to
  bad estimation.

### Termination

Each iteration strictly reduces at least one `|A_d|` (the chosen one drops from `|A_d|`
to `k < |A_d|`; sibling dim projections can only shrink or stay equal). The loop
terminates when one of:

- **Exit #1 (`M <= target`)**: target met, build the shrunk query.
- **Exit #2 (`decision.isNone()`)**: no productive shrink possible (every dim at one
  value, or the proportional ratio would not actually shrink the chosen dim).
- **Exit #3 (`iter >= MAX_SHRINK_ITERATIONS`)**: defensive cap. WARN-logged.

In the common case (symmetric cubes, small dim count), 1-3 iterations suffice.
`MAX_SHRINK_ITERATIONS = 32` exists for pathological DSDs only.

### Truncation step

Owned by `AdapterRouter`. After `getShrunkQuery` returns the shrunk query, the router:

1. Calls `genericRegistryAdapter.getData(shrunkQuery)` to fetch the raw stream.
2. Calls `truncatorProvider.forFormat(returnFormat).truncate(raw, N, sdmxBeans)`.

The truncator is selected by the registry's `defaultFormat`. Implementations ship for:

- `JSON_1_0_0` -- `JsonDataV10SeriesLimitTruncator` (series is a map keyed by series-key
  string). Streaming Jackson.
- `JSON_DATA_2_0_0` -- `JsonDataV20SeriesLimitTruncator` (series is an array of objects).
  Streaming Jackson.
- `CSV_DATA_2_0_0` -- `CsvSeriesLimitTruncator`. Parses the header to locate dimension
  columns by name (DSD-driven, excluding time dim), counts distinct series keys built
  from those columns, keeps rows for the first `N` distinct keys.
- `XML_GENERICDATA_2_1` and `XML_STRUCTURE_SPECIFIC_2_1` -- `XmlSeriesLimitTruncator`
  (StAX `XMLEventReader`/`Writer`, matches local-name `Series` -- works for both
  flavors).

All implementations stream end-to-end via `StreamingFixtureIO` (the same piped-stream +
virtual-thread driver used by data fixtures, see design 013). No buffering of the full
response.

### `N <= 0` short-circuit

`AdapterRouter` guards: when `n <= 0`, returns `truncator.emptyStream(beans)` directly --
no shrink, no upstream call. The truncator's empty payload is a minimal well-formed
response in its own format (e.g. `{"meta":{"id":"empty"},"data":{"dataSets":[]}}` for
JSON; `<message:GenericData><message:DataSet/></message:GenericData>` for XML; header-only
row for CSV). Belt-and-suspenders against a controller-level path that reaches the router
with a non-positive limit.

### Failure modes

- **Availability fails (timeout, 5xx, circuit breaker open)**: the prober propagates the
  `FeignException` to the caller. No fallback -- guessing without availability is worse
  than failing.
- **Shrink cannot reduce `M` below `target`**: proceeds with the current filter / key.
  Truncator still caps at `N`.
- **Data request returns fewer than `N` series ("undershoot")**: emit all available
  series. No special log -- the truncator simply outputs less than `N`.
- **Unsupported data format**: when a registry with `supportsLimit: false` has a
  `defaultFormat` for which no truncator is registered, the first request fails with a
  clear `IllegalStateException("No SeriesLimitTruncator registered for return format
  X")`. There is no startup-time validator -- the configuration is fetched from a config
  server at runtime and may change between boots, so boot-only validation has limited
  value.

## Architecture

See `architecture.puml`.

### 1. Config module (`sdmx-proxy-config/`)

#### `DataEndpointConfiguration` -- new fields

| Field                               | Default | Description                                                                                                                 |
|-------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------|
| `supportsLimit`                     | `true`  | When false, the proxy emulates `limit` via availability probing and streaming truncation.                                   |
| `limitEmulationTolerance`           | `1.2`   | Overshoot factor: `target = floor(limit * tolerance)`. Higher = fewer probes, larger truncation slack. Range `[1.0, 10.0]`. |
| `limitEmulationMaxShrinkIterations` | `32`    | Hard cap on shrink iterations. Range `[1, 256]`.                                                                            |

`sdmx-proxy-config/README.md` documents these in the schema table.

### 2. Main module (`sdmx-proxy/`) -- new package `services.limit`

| Type                              | Kind                      | Role                                                                                           |
|-----------------------------------|---------------------------|------------------------------------------------------------------------------------------------|
| `LimitEmulationService`           | interface                 | Single method `getShrunkQuery(query, sdmxBeans, prober)`.                                      |
| `LimitEmulationServiceImpl`       | `@Service`                | Shrink loop; branches on `SdmxVersion`. No `GenericRegistryAdapter` dep.                       |
| `AvailabilityProber`              | functional interface      | `InputStream probe(TranslatedAvailabilityQuery)`. Callback supplied by `AdapterRouter`.        |
| `AvailabilityProjection`          | Java record               | `(valuesByDimensionId, seriesCount)` + `effectiveSeriesCount()` + `combinatorialUpperBound()`. |
| `AvailabilityResponseParser`      | interface                 | Format-aware parse to `AvailabilityProjection`.                                                |
| `JsonAvailabilityResponseParser`  | `@Component`              | SDMX-JSON 2.0.0 structure; handles `components` and `keyValues`; reads `series_count`.         |
| `FilterShrinker`                  | interface (pure function) | One decision per call: `decide(projection, target, timeDimId)`.                                |
| `FilterShrinkerImpl`              | `@Component`              | argmax-by-cardinality + proportional `ceil` keep count.                                        |
| `ShrinkDecision`                  | `@Value`                  | `(dimensionId, retainedValues)`; `none()` sentinel.                                            |
| `KeyParser`                       | interface                 | `parseKey` / `buildKey` for SDMX positional keys. Used by 2.1 path.                            |
| `KeyParserImpl`                   | `@Service`                | Stateless implementation.                                                                      |
| `SeriesLimitTruncator`            | interface                 | `truncate(raw, n, sdmxBeans)` + `emptyStream(sdmxBeans)`. One impl per `ReturnFormat`.         |
| `SeriesLimitTruncatorProvider`    | `@Component`              | Lookup by `ReturnFormat`; collected from registered `SeriesLimitTruncator` beans.              |
| `JsonDataV10SeriesLimitTruncator` | `@Component`              | SDMX-JSON 1.0.0 (series-as-map). Streaming Jackson.                                            |
| `JsonDataV20SeriesLimitTruncator` | `@Component`              | SDMX-JSON 2.0.0 (series-as-array). Streaming Jackson.                                          |
| `CsvSeriesLimitTruncator`         | `@Component`              | SDMX-CSV 2.0.0. DSD-driven series-key derivation.                                              |
| `XmlSeriesLimitTruncator`         | `@Component`              | SDMX-ML generic + structure-specific. StAX, local-name match.                                  |

### 3. `AdapterRouterImpl` modifications

Inject `LimitEmulationService` and `SeriesLimitTruncatorProvider`. New private helper:

```java
private InputStream resolveRawDataStream(
        TranslatedDataQuery query,
        SdmxBeans sdmxBeans,
        boolean emulateLimit
) {
    if (!emulateLimit) {
        return genericRegistryAdapter.getData(query);
    }
    int n = query.getLimit() == null ? 0 : query.getLimit();
    SeriesLimitTruncator truncator = truncatorProvider.forFormat(query.getReturnFormat());
    if (n <= 0) {
        return truncator.emptyStream(sdmxBeans);
    }
    TranslatedDataQuery shrunkQuery = limitEmulationService.getShrunkQuery(
            query, sdmxBeans, genericRegistryAdapter::getAvailability);
    InputStream raw = genericRegistryAdapter.getData(shrunkQuery);
    return truncator.truncate(raw, n, sdmxBeans);
}
```

Called from `getData` after the bypass check, when `emulateLimit = limit != null &&
dataConfig != null && !dataConfig.isSupportsLimit()`.

### 4. Registry config

`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` -- BIS SDMX 3.0
`dataEndpointConfig` carries `"supportsLimit": false`. All other registries default to
`true` (passthrough).

### 5. `TranslatedDataQuery` modification

`@Builder(toBuilder = true)` so `LimitEmulationServiceImpl` can build the shrunk query
via `query.toBuilder().filters(...).limit(null).build()` (3.0) or `.key(...).filters(null
).limit(null).build()` (2.1) without copying every field by hand.

`TranslatedAvailabilityQuery` keeps plain `@Builder` -- `toBuilder` is unused on it.

## Files affected

### New

- `sdmx-proxy/.../services/limit/AvailabilityProber.java`
- `sdmx-proxy/.../services/limit/AvailabilityProjection.java`
- `sdmx-proxy/.../services/limit/AvailabilityResponseParser.java`
- `sdmx-proxy/.../services/limit/JsonAvailabilityResponseParser.java`
- `sdmx-proxy/.../services/limit/FilterShrinker.java`
- `sdmx-proxy/.../services/limit/FilterShrinkerImpl.java`
- `sdmx-proxy/.../services/limit/ShrinkDecision.java`
- `sdmx-proxy/.../services/limit/KeyParser.java`
- `sdmx-proxy/.../services/limit/KeyParserImpl.java`
- `sdmx-proxy/.../services/limit/LimitEmulationService.java`
- `sdmx-proxy/.../services/limit/LimitEmulationServiceImpl.java`
- `sdmx-proxy/.../services/limit/truncate/SeriesLimitTruncator.java`
- `sdmx-proxy/.../services/limit/truncate/SeriesLimitTruncatorProvider.java`
- `sdmx-proxy/.../services/limit/truncate/JsonDataV10SeriesLimitTruncator.java`
- `sdmx-proxy/.../services/limit/truncate/JsonDataV20SeriesLimitTruncator.java`
- `sdmx-proxy/.../services/limit/truncate/CsvSeriesLimitTruncator.java`
- `sdmx-proxy/.../services/limit/truncate/XmlSeriesLimitTruncator.java`
- `sdmx-proxy-e2e/.../tests/LimitEmulationE2ETest.java` -- targeted BIS scenarios.
- `sdmx-proxy-e2e/.../tests/framework/config/LimitTestSuitConfiguration.java` -- per-registry config DTO for the generic
  limit diagnostics.

### Modified

- `sdmx-proxy-config/.../configuration/data/DataEndpointConfiguration.java` -- adds three fields.
- `sdmx-proxy-config/README.md` -- schema table rows for the three fields.
- `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` -- BIS 3.0 `supportsLimit: false`.
- `sdmx-proxy/.../common/data/TranslatedDataQuery.java` -- `@Builder(toBuilder = true)`.
- `sdmx-proxy/.../services/adapter/AdapterRouterImpl.java` -- inject service + provider; `resolveRawDataStream` helper.
- `sdmx-proxy-e2e/.../tests/framework/BaseRegistryTestSuite.java` -- two generic limit tests (
  `testLimitNativelyHonored`, `testLimitEmulationStrict`); the latter is `@ParameterizedTest` over
  `LimitTestSuitConfiguration.registryReturnFormats`.
- `sdmx-proxy-e2e/.../tests/framework/config/RegistryTestSuitConfiguration.java` -- adds optional
  `limitTestSuitConfiguration` field.
- `sdmx-proxy-e2e/.../registry/bis/3_0/bis_3_0_test_config.json` -- adds `limitTestSuitConfiguration` block listing 4
  formats.
- `sdmx-proxy-e2e/.../registry/imf/3_0/imf_3_0_test_config.json` -- adds `limitTestSuitConfiguration` block listing JSON
  2.0.
- `sdmx-proxy-e2e/.../resources/log-patterns/allowed-errors.properties` -- allows benign startup WARNs that expect a
  runtime-POSTed config.

### Unit tests (new)

- `FilterShrinkerImplTest`
- `JsonAvailabilityResponseParserTest`
- `AvailabilityProjectionTest`
- `KeyParserImplTest`
- `LimitEmulationServiceImplTest`
- `JsonDataV10SeriesLimitTruncatorTest`
- `CsvSeriesLimitTruncatorTest`
- `XmlSeriesLimitTruncatorTest`

### No changes required

- `QueryTranslatorImpl` -- emulation decision is orthogonal to translation.
- `DataQuery30Controller` -- limit already flows to the translator.
- `GenericRegistryAdapterImpl` / `Sdmx30DataClient` / `Sdmx30AvailabilityClient` -- the
  emulation composes existing adapter calls.
- `StreamingDataConversionService` / sdmx-core reader engines -- the truncator shapes the
  raw registry stream; conversion sees a smaller but well-formed payload.
- `DataFixture` subsystem -- fixtures continue to run on the truncated stream.

## Edge cases

- **`limit` absent on the request**: `emulateLimit` is false regardless of the flag.
  Passthrough.
- **`limit <= 0`**: `AdapterRouter` short-circuits to `truncator.emptyStream(beans)` -- no
  probe, no upstream call. Controller-level rejection with HTTP 400 is the preferred
  user-facing behavior; this is defense in depth.
- **Projection upper bound `<= target` on first probe**: skip the loop, return query
  unchanged (except `.limit(null)`).
- **Every dim already at one value** (very narrow filter): shrink returns `none()`, loop
  exits, data call proceeds against the current filter.
- **Time dimension**: never chosen by `FilterShrinker`. Time filtering stays on
  `startPeriod` / `endPeriod`, which are preserved through `toBuilder`.
- **Empty projection on some dim** (`|A_d| == 0`): `combinatorialUpperBound` returns `0`,
  fast-path fires. Data call against the current filter; registry responds with empty
  cube; truncator emits the empty payload.
- **Shrink iteration cap reached**: WARN-logged, loop exits with whatever filter / key
  was last computed.
- **Concurrent requests to the same dataflow**: no shared mutable state. Service is
  thread-safe by construction.
- **Cache interaction**: data responses are not cached; structures are. Availability
  responses are not cached today (`AdapterRouterImpl.getAvailability` has no cache
  lookup). If availability caching is introduced later, emulation probes benefit
  automatically.
- **Long overflow in `combinatorialUpperBound`**: `Math.multiplyExact` throws on
  overflow; the projection method catches it and returns `Long.MAX_VALUE`. Realistic DSDs
  do not approach this -- 7 dimensions × 100 values = 10^14 fits in `long`. Defensive
  only.
- **Existing `mergeAllWildcardKey`**: respected. The 2.1 shrink rebuilds the key with
  this flag honored on both data and availability paths. The 3.0 shrink does not touch
  the key, so this flag is irrelevant on that path.
- **Non-determinism of the selected subset**: the truncator emits the first `N` series in
  the registry's response order. BIS does not contract a stable ordering across repeat
  identical requests, so two identical client requests may return disjoint subsets. This
  was an explicit design decision and is acceptable for V1.

## Verification

### Unit tests

Run with:

```
./gradlew :sdmx-proxy:test --tests "com.epam.sdmxproxy.services.limit.*"
```

All limit-package tests must be green. Key invariants under test:

- **`FilterShrinkerImplTest`** -- argmax selection, time dim exclusion, single-value-dim
  skip, proportional `k`, `ceil` rounding, terminal cases.
- **`JsonAvailabilityResponseParserTest`** -- BIS `keyValues` shape, IMF `components`
  shape, `series_count` annotation extraction, dedup + order preservation, multi-region
  merge.
- **`AvailabilityProjectionTest`** -- combinatorial product, empty-dim short-circuit,
  overflow saturation, annotation-vs-fallback selection in `effectiveSeriesCount`.
- **`KeyParserImplTest`** -- parse `null` / `""` / `*` / `all` / positional / `+`-OR /
  empty positions; build with and without `mergeAllWildcard`; round-trip symmetry.
- **`LimitEmulationServiceImplTest`** -- 3.0 path (filter shrink, key untouched) and 2.1
  path (key shrink, filters nulled out, concrete componentId on availability,
  `startPeriod` preserved, `all` keyword expanded). Verifies the service never sees
  `GenericRegistryAdapter` -- probes flow through a recorded `AvailabilityProber`
  lambda.
- **Truncator tests** (one per format) -- emits first `N`, undershoot emits all,
  envelope preserved, empty stream is well-formed.

### End-to-end tests

The generic diagnostics in `BaseRegistryTestSuite` run for every registry suite that
declares a `limitTestSuitConfiguration`:

- **`testLimitNativelyHonored`**: forces `supportsLimit: true`, issues a request with
  `?limit=N`, parses the JSON response, asserts series count `<= N`. Soft-aborts
  (`Assumptions.abort`) with an actionable WARN if the registry ignores native `limit`
  -- the test surfaces onboarding friction without failing the build.
- **`testLimitEmulationStrict`**: `@ParameterizedTest` over
  `limitTestSuitConfiguration.registryReturnFormats`. For each format: forces
  `supportsLimit: false`, sets that format as `defaultFormat` (extending
  `supportedFormats` if needed), issues the request, parses the response, asserts series
  count `<= N`. **Hard-fails** if emulation returns more than `N` series. Soft-aborts
  when the registry has no enabled availability endpoint or when the format is not in
  the truncator-supported set.

Run:

```
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*RegistryTestSuit"
```

Per-registry coverage today:

- **`BIS_3_0_RegistryTestSuit`** -- emulation strict over `JSON_1_0_0`, `CSV_DATA_2_0_0`,
  `XML_GENERICDATA_2_1`, `XML_STRUCTURE_SPECIFIC_2_1`. Native test SKIPs with WARN
  (BIS does not honor `limit` natively).
- **`IMF_3_0_RegistryTestSuit`** -- emulation strict over `JSON_DATA_2_0_0`. Native test
  outcome depends on whether IMF honors `limit` natively.

Targeted scenarios outside the parameterization fit in `LimitEmulationE2ETest`:

```
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.LimitEmulationE2ETest"
```

Loads the main BIS config and flips `supportsLimit=false` programmatically in
`@BeforeAll`. Four `@Test` methods:

1. Strict bound on a wide cube (`limit=5`, `WS_EER`, `key=*`).
2. Large-limit passthrough (`limit=100000`).
3. Complex client key preserved (`key=M.N.B.*`, `limit=5`).
4. Client `c[]` filter alongside `limit` (`c[FREQ]=M`, `limit=5`).

### Manual smoke test

```
./gradlew :sdmx-proxy:bootRun
```

Issue the original failing request:

```
GET http://localhost:8050/api/v0/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/*?includeHistory=false&limit=10&attributes=all&dimensionAtObservation=TIME_PERIOD
Accept: application/vnd.sdmx.data+json;version=2.0.0
```

Expected proxy logs (with `FEIGN_LOG_LEVEL=BASIC`):

- `Limit emulation engaged: registry=BIS, sdmxVersion=SDMX_3_0, ..., limit=10, target=12`
- 1-3 `GET .../availability/...` round-trips.
- Per probe: `Probe #N: M=..., target=12, dims={...}, shrinkNeeded=...`.
- Per shrink: `Shrink iteration N: narrow dim 'X' from S to k values (retained: [...])`.
- One `GET .../data/...` with `c[X]=...,...` filters and no `limit` parameter.
- The client response body contains at most 10 `series` entries.

Control: same URL against IMF (which has `supportsLimit: true` by default) -- proxy
passes `limit` through unchanged; no availability probes in the log; no
`Limit emulation engaged` line.

## SDMX standard references

- SDMX-REST 2.2.0 availability:
  `sdmx-rest-2.2.0/doc/3_2_availability_queries.md`. Mode `exact` is what the emulation
  uses -- projections describe only values that actually appear in the filtered cube.
- SDMX-JSON 2.0.0 structure response (availability uses the structure response shape for
  content constraints): `sdmx-json-2.0.0/structure-message/`.
- SDMX-REST 2.2.0 key syntax:
  `sdmx-rest-2.2.0/doc/data.md` -- positional, `.`-separated, `+` for OR within a
  position. `c[X]` filters: at most once per Component; `,` for OR, `+` for AND.

## Open questions / deferred

- **Caching availability probes**: each probe is one HTTP round-trip. For hot dataflows,
  caching per `(agency, resource, version, filter)` for a short TTL would cut latency.
  Revisit once log-based observability shows probe latency dominating emulation
  wall-time.
- **Gather-to-N via multiple data queries**: if undershoot proves common in practice,
  the follow-up is the partition-and-gather approach. Requires streaming merge of SDMX
  responses -- material additional work. Track as a separate issue if it materializes.
- **Parallel shrink candidates**: try multiple dim-shrink candidates in parallel each
  iteration and pick the best. Pure latency optimization. Defer.
- **Tolerance tuning**: `1.2` is a per-registry `DataEndpointConfiguration` field; tune
  without code changes if log analysis suggests a different optimum.
- **`FilterTranslatorImpl.mergeFiltersIntoKey`** silently truncates multi-value `c[]` to
  `values.get(0)` on the SDMX 2.1 translation path. Currently unreachable through legit
  traffic because `FilterValidatorImpl` rejects multi-value `c[]` on 2.1 before merge.
  Tracked separately. The limit-emulation 2.1 path does *not* go through this helper --
  it uses `KeyParser.buildKey` which preserves `+`-OR within a position.
- **Metrics**: log-based today. If operators need histograms / counters, a thin
  Micrometer wrapper can be added on top of the existing log points without changing
  the emulation logic.
