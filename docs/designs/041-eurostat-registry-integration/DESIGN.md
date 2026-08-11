# Design: Eurostat registry integration

Status: Implemented
Author: design-feature skill
Related: 040 (availability emulation -- lands on the same ESTAT registry; see the scope note),
039 (SDMX 2.1 request translation, `Sdmx21QueryNormalizer`, 429 retry `Client` decorator),
036 (XML 3.0 structure **output**), 016 (`convertKeyToFilters`), 014 (limit emulation)

Renumbered from 040 to 041: design 040 was taken by availability emulation, which was authored in
parallel and pushed first. References of the form `Design 041 / P<n>` in test javadoc point here;
the lowercase `see design 040` references in the availability and cache classes point there.

## Context

Pointing the proxy at Eurostat failed on every request, on both the SDMX 2.1 and the SDMX 3.0
endpoint. A defect inventory taken from live probes on 2026-09-04 recorded 11 proxy defects
(`P1`..`P11`) and 8 Eurostat behaviors (`E1`..`E8`). This design fixes the subset the user
selected; the numbering is kept so the design and the inventory read together.

Eurostat base URLs:

- SDMX 2.1: `https://ec.europa.eu/eurostat/api/dissemination/sdmx/2.1/`
- SDMX 3.0: `https://ec.europa.eu/eurostat/api/dissemination/sdmx/3.0/`

### Scope

In scope, with the decision taken for each:

| Defect | Decision |
|--------|----------|
| `P1` gzip without `Content-Encoding` | New `disableCompression` flag on the **registry version**, sending `Accept-Encoding: identity` |
| `P2` cannot parse SDMX-ML 3.0 structures | Add the switch arm. sdmx-core already reads 3.0; see the following section |
| `P3` `dataconstraint` not translated for 2.1 | Map to `contentconstraint` in the 2.1 normalizer |
| `P4` 2.1 data path drops query parameters | Forward every parameter the SDMX 2.1 REST data resource defines |
| `P5` `formatInstant` always throws | Use `ISO_INSTANT` |
| `P8` config change doesn't flush the cache | Flush on an actual change |
| `P9` `references` not clamped | Declare the accepted set per registry, scope-aware, with a downgrade map |
| `P10` `+` passed through to 2.1 | Map to `latest` in `toSdmx21PathSlot` |
| `E7` 3.0 rejects empty key positions | Configuration only. The flag already exists |

Out of scope, deliberately:

- `P6` (limit emulation propagates the availability probe failure), `P7` (`availabilityEnabled`
  is read nowhere), and `E1` (Eurostat serves no availability) are handled in a parallel worktree
  that owns limit emulation. Do not touch `services/limit/` or
  `AvailabilityEndpointConfiguration` in this change.

  **Resolved since.** That worktree landed as design 040 and was merged on top of this one.
  `P7` is closed: `availabilityEnabled` is now read, and `false` routes the request to
  `emulation` or answers 501. `E1` is answered by emulating availability from a series-key data
  query. `P6` is closed by prohibition rather than by a fix -- its `ConfigValidator` rejects
  `supportsLimit: false` together with emulated availability, because a limit-emulation probe
  would itself become a data request. ESTAT therefore ships `supportsLimit: true`, so `limit` is
  passed through to Eurostat, which ignores it. Narrowing a response still relies on
  `firstNObservations` / `lastNObservations` (`P4`).
- `P11` (`context=datastructure`) is a won't-fix. Eurostat serves no `datastructure` data context,
  and clients send `dataflow`.
- `E6` (multi-value keys) is deferred. The facts are recorded in the Edge cases section so the
  follow-up does not have to re-probe.

### The P2 finding that shapes this design

The inventory recorded `P2` as "the proxy can't parse SDMX-ML 3.0 structures" and estimated a new
reader as the largest item on the list. Reading sdmx-core shows that is not so.

`io.sdmx.format.ml.factory.structure.SdmxMLStructureReaderFactory` already dispatches on the
document's own namespace rather than the requested media type
(`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/factory/structure/SdmxMLStructureReaderFactory.java`,
`getStaxStructureReaderEngine`):

```java
case VERSION_TWO :
    return StaxStructureReaderEngineV2.getInstance();
case VERSION_TWO_POINT_ONE :
    return StaxStructureReaderEngineV21.getInstance();
case VERSION_THREE :
    return StaxStructureReaderEngineV3.getInstance();
```

`StaxStructureReaderEngineV3` is a complete implementation with roughly 35 per-artefact sub-readers
under
`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/engine/structure/reader/v3/`, and
`SdmxConstants.namespacesV3` contains `STRUCTURE_NS_3_0`
(`http://www.sdmx.org/resources/sdmxml/schemas/v3_0/structure`), so
`SdmxMessageUtil.getSchemaVersion` resolves a Eurostat 3.0 structure document to `VERSION_THREE`
(`sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/constants/SdmxConstants.java`,
lines 82 and 232).

The limitation is the proxy's own dispatch. `StreamingStructureConversionService.getParserFactory`
mapped only `XML_STRUCTURE_2_1` to that bean, so `XML_STRUCTURE_3_0_0` fell through to the
`default` arm. `P2` is therefore a switch arm on the bean the proxy already injects, and design
036's non-goal ("no native XML 3.0 *reading*") is satisfied by the library rather than by new proxy
code.

`StreamingStructureConversionServiceTest.shouldParseSdmxMl30StructureDocument` is the evidence for
this claim, against a live Eurostat 3.0 fixture.

This also removes the need for the `detail`-stripping workaround the inventory proposed for `E4`.
With the 3.0 registry configured as `XML_STRUCTURE_3_0_0`, Eurostat accepts `detail=full`, and the
`406` only ever appeared because the probe asked for a 2.1 representation from the 3.0 endpoint.

## Problem

### P1 -- gzip responses without `Content-Encoding` reach the SDMX readers raw

Eurostat gzips the body when the request carries exactly `Accept-Encoding: gzip`, and sets no
`Content-Encoding`. It marks the compression only in `Content-Disposition`. OkHttp decompresses on
`Content-Encoding` alone, so the SDMX readers receive a gzip stream.

Measured against `codelist/ESTAT/FREQ/latest`:

| `Accept-Encoding` | `Content-Length` | `Content-Disposition` |
|-------------------|-----------------:|-----------------------|
| `gzip` | 1,262 | `ESTAT_FREQ_3.9.xml.gz` |
| `identity` | 7,778 | `ESTAT_FREQ_3.9.xml` |
| `gzip, deflate` | 7,778 | `ESTAT_FREQ_3.9.xml` |

The failure differs by path, and none of the three messages names compression:

| Path | Symptom |
|------|---------|
| Structure | `NullPointerException` in `StructureMapperImpl.map` (line 57), because `parseStructures` returned `null` |
| Data, XML | `io.sdmx.api.exception.SdmxSyntaxException: 800` |
| Data, CSV | `NullPointerException: Cannot invoke "DataReaderEngine.reset()" because "reader" is null` |

The structure `null` is not incidental. `SdmxMLStructureReaderFactory.getSdmxBeans` catches
`Throwable`, restores the trace flag, and returns `null`, so an unreadable body is
indistinguishable from an unsupported one at the call site.

### P2 -- `XML_STRUCTURE_3_0_0` has no parser arm

`StreamingStructureConversionService.getParserFactory` accepted `XML_STRUCTURE_2_1`,
`JSON_DATA_1_0_0`, and `JSON_STRUCTURE_2_0_0`; any other format threw
`UnsupportedConversionException`. `StreamingAvailabilityConversionService.getParserFactory` carried
the identical switch.

Eurostat 3.0 serves structures as `application/vnd.sdmx.structure+xml` in version `3.0.0` or `2.1`
only. It answers `406` for `application/vnd.sdmx.structure+json;version=2.0.0`. So a 3.0 registry
configured with the format Eurostat actually serves could not be read. Observed: every structure
request returned `500`, and every data and availability request returned
`501 Unsupported return format for parsing: XML_STRUCTURE_3_0_0`, because the data path resolves
the DSD through the structure parser before it queries data.

### P3 -- the SDMX 3.0 structure type `dataconstraint` is not translated for 2.1 registries

`structure/dataconstraint/ESTAT/NAMA_10_GDP/+` against the 2.1 registry emitted
`GET /2.1/dataconstraint/ESTAT/NAMA_10_GDP/%2B?detail=full`, which returns `405`. The 2.1 resource
name is `contentconstraint`, and Eurostat serves it. The string `contentconstraint` did not appear
anywhere in `sdmx-proxy/src/main`.

The same request against the 3.0 registry returns 4,046 bytes, so only the version translation was
missing. Eurostat's per-dataflow content constraint is the only value-set information it publishes,
so this closed the sole path to it on the 2.1 endpoint.

### P4 -- the 2.1 data path drops every query parameter except the period bounds

`GenericRegistryAdapterImpl.getData21` built its query map from `startPeriod` and `endPeriod` only,
under a `//TODO support other query params` comment.

Measured against `TPS00001`, where the unfiltered response is 17,393 bytes:

| Request parameter | Outbound query | Response |
|-------------------|----------------|---------:|
| `lastNObservations=1` | none | 17,393 |
| `firstNObservations=1` | none | 17,393 |
| `updatedAfter=2024-01-01T00:00:00Z` | none | 17,393 |

Eurostat honors both observation counts. Asked directly, a 57,696-byte response drops to 13,188
bytes for `lastNObservations=1` and 12,867 bytes for `firstNObservations=1`. The 3.0 path already
forwarded them: 7,780 and 7,710 bytes against a 17,401-byte baseline.

### P5 -- `formatInstant` always throws

`GenericRegistryAdapterImpl.formatInstant` called `DateTimeFormatter.ISO_DATE_TIME.format(instant)`.
`ISO_DATE_TIME` requires a year field. `Instant` has none, so the call always threw
`java.time.temporal.UnsupportedTemporalTypeException: Unsupported field: Year`. Four call sites: the
2.1 data path, the 3.0 data path (twice), and the availability path.
`updatedAfter=2024-01-01T00:00:00Z` against a 3.0 registry returned `500`. The 2.1 data call never
reached it only because `P4` discarded `updatedAfter` first, so fixing `P4` without `P5` would have
turned a silent drop into a `500`.

This defect is registry-independent.

### P8 -- a configuration change does not flush the cache

`ConfigController.updateConfig` called `proxyConfigurationProvider.setRuntimeOverride` and touched
no cache. `CacheKeyGenerator` keys carry the registry *name* but nothing identifying the
configuration, so entries written under one registry configuration were served under the next.

Observed while probing: after switching the `ESTAT` registry from the 2.1 to the 3.0
configuration, structure requests returned byte-identical 2.1 results in under 15 ms with no
outbound call, while `dataflow/ESTAT/*/*` -- too large to cache -- went to the 3.0 endpoint and
returned `500`. A whole probe run had to be discarded.

`ConfigServerPoller.poll` reaches `ConfigServerConfigExtractor.pollForUpdate`, which called
`cachedConfig.set(config)` unconditionally every 30 seconds, so the production path had the same
gap. It also means the fix must compare before flushing: an unconditional flush on every poll would
empty the cache twice a minute.

### P9 -- `references` is forwarded without checking what the registry accepts

`Sdmx21QueryNormalizerImpl.toReferences` rewrites `none` to `null` and forwards every other value
unchanged. `QueryTranslatorImpl` defaults the 3.0 path to `"none"` and otherwise forwards verbatim.

The accepted set depends on whether the query names one artefact or all of them. Probed on
2026-09-04:

| Version | Scope | `none` | `children` | `descendants` | `datastructure`, `parents`, `parentsandsiblings`, `all` |
|---------|-------|--------|------------|---------------|--------------------------------------------------------|
| 2.1 | one artefact | 200 | 200 | 200 | `400` |
| 2.1 | `all` | 200 | `400 ERR_ALL_FLOWS_REFERENCES` | `400 ERR_ALL_FLOWS_REFERENCES` | `400` |
| 3.0 | one artefact | 200 | 200 | 200 | `400 INVALID_URL_PARAMETER_VALUE` |
| 3.0 | `*/*` | 200 | 200, ignored | 200, ignored | `400` |

The 3.0 wildcard row is the dangerous one. All three values return an identical 36,900,709 bytes,
so `children` and `descendants` succeed with the references silently absent. A client that depends
on the references arriving gets a successful response and no signal.

`references=datastructure` is what StatGPT sends, and is the shape of the originally reported
failure.

### P10 -- the version keyword `+` is passed through to 2.1 registries

`Sdmx21QueryNormalizerImpl.toSdmx21PathSlot` mapped `*` and the empty string to `all` and left
everything else alone, so the SDMX 3.0 keyword `+` reached the registry verbatim: flow references
such as `ESTAT,TPS00001,+` and structure paths ending in `/%2B`. The 2.1 spelling is `latest`.

Eurostat tolerates it. Another 2.1 registry need not.

## Current architecture

```
GET /sdmx/3.0/structure/{type}/{agency}/{id}/{version}
  -> SdmxStructureController
  -> QueryTranslatorImpl.translateStructureQuery
        agency routing -> registry + version config
        checkStructureTypeIsSupported(type, versionConfig)
        references: resolver clamp, then 2.1 toReferences            <- P9
        determineStructureReturnFormat -> SdmxFormat
  -> AdapterRouterImpl.getStructures
        cache lookup (CacheKeyGenerator)                             <- P8
        bypass | conversion
  -> GenericRegistryAdapterImpl.getStructures21 / getStructures30
        toSdmx21StructureType(structure.type())                      <- P3
        toSdmx21PathSlot(agency, id, version)                        <- P10
  -> SdmxApiClientProviderImpl.getStructure21Client / ...30Client
        buildClient: Resilience4jFeign
          .client(rateLimitRetry.wrap(identityEncoding.wrap(ok)))    <- P1
  -> Feign -> OkHttp -> registry
  <- StreamingStructureConversionService.parseStructures
        getParserFactory(registryFormat)                             <- P2
```

The data path is the same shape through `QueryTranslatorImpl.translateDataQuery` ->
`GenericRegistryAdapterImpl.getData21` / `getData30` (`P4`, `P5`, `E7`).

## Solution

Nine independent changes, each small, ordered so no step depends on a later one.

`P1` follows the decorator pattern design 039 established for HTTP 429: a `feign.Client` wrapper
selected per registry by a provider, chained inside `SdmxApiClientProviderImpl.buildClient`.
Compression is a transport concern shared by all three endpoints of a registry version, so the flag
lives on `VersionSpecificRegistryConfiguration` alongside `resilienceConfig` rather than being
repeated three times.

`P2` adds one switch arm in each of the two conversion services, pointing at the
`sdmxMLStructureReaderFactory` bean already injected there.

`P3` and `P10` are rules on `Sdmx21QueryNormalizer`, joining `toKey`, `toComponentId`,
`toReferences`, and `toSdmx21PathSlot`. Both are idempotent, matching that interface's contract.

`P9` introduces three fields on `StructureEndpointConfiguration` and a resolver invoked from
`QueryTranslatorImpl`. Two accepted sets are needed because the accepted values differ by scope, and
a downgrade map is needed because the goal is to make `references=datastructure` work rather than to
return a tidier error.

`P4`, `P5`, and `P8` are localized fixes with no new configuration.

`E7` needs no code. `DataEndpointConfiguration.replaceEmptyDimensionsWithWildcard` already exists
and `QueryTranslatorImpl` already applies it. The ESTAT 3.0 registry configuration sets it to
`true`.

## Implementation plan

### Step 1: Add the `disableCompression` flag (P1)

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/VersionSpecificRegistryConfiguration.java`

Add a `private boolean disableCompression;` field beside the existing endpoint configurations,
documented as a transport concern that covers all three endpoints. Default `false` keeps every
existing registry byte-identical on the wire.

### Step 2: Add the identity-encoding `Client` decorator (P1)

**New:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/IdentityEncodingClient.java`

A `feign.Client` decorator modelled on `RateLimitRetryClient` in the same package. It rebuilds the
request with exactly one `Accept-Encoding: identity` header, removing every existing spelling first
because HTTP header names are case-insensitive while Feign's header map is a plain `Map`. Use
`Request.create(HttpMethod, String, Map, byte[], Charset, RequestTemplate)` -- present in Feign
13.6 -- so `requestTemplate()` survives for Feign's logging and decoders.

**New:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/IdentityEncodingClientProvider.java`

Returns the delegate unchanged when the registry has not opted out.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/SdmxApiClientProviderImpl.java`

Inject the provider and chain it inside the existing `.client(...)` call:

```java
.client(rateLimitRetryClientProvider.wrap(identityEncodingClientProvider.wrap(baseOkHttpClient, selectedRegistry), selectedRegistry))
```

The identity wrapper sits closest to OkHttp, so a 429 replay re-enters it and the header is
reapplied on every attempt. Do **not** put this in `FeignConfig.baseOkHttpClient`: that bean is
shared by every registry, and a global `identity` would drop compression for BIS, IMF, and OECD too.

### Step 3: Add the `XML_STRUCTURE_3_0_0` parser arm (P2)

**Files:** `StreamingStructureConversionService.java` and `StreamingAvailabilityConversionService.java`
(both under `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/conversion/`)

```java
case XML_STRUCTURE_2_1, XML_STRUCTURE_3_0_0 -> sdmxMLStructureReaderFactory;
```

No new bean and no new import: `sdmxMLStructureReaderFactory` is already a field on both services.
The writer side already handles 3.0 (design 036). Do not add `XML_DATA_3_0_0` to
`StreamingDataConversionService` -- out of scope by decision, since Eurostat 3.0 serves
`CSV_DATA_2_0_0`, which the proxy already reads.

### Step 4: Fix `formatInstant` (P5)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java`

Switch to `DateTimeFormatter.ISO_INSTANT`. Do this before Step 5: forwarding `updatedAfter` on the
2.1 path while `formatInstant` still throws would convert a silent drop into a `500`.

### Step 5: Forward the remaining 2.1 data query parameters (P4)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java`

Replace the `getData21` parameter map with a `putIfPresent` sequence covering `startPeriod`,
`endPeriod`, `updatedAfter`, `firstNObservations`, `lastNObservations`, `dimensionAtObservation`,
`includeHistory`, and a folded `detail`.

`TranslatedDataQuery` carries no `detail` field, and should not: SDMX 3.0 dropped the data `detail`
parameter in favour of `attributes` and `measures`, and the proxy's public API is 3.0. A private
`toSdmx21Detail(attributes, measures)` folds the pair back into the single enumerated value a 2.1
registry understands -- `none`+`none` to `serieskeysonly`, `measures=none` to `nodata`,
`attributes=none` to `dataonly`, otherwise null so the parameter is dropped.

`limit`, `asOf`, and `skipEmptySeries` are 3.0 additions with no 2.1 equivalent and stay off the 2.1
path. `limit` in particular must not be forwarded -- a 2.1 registry ignores it, which is the
silent-truncation failure limit emulation exists to avoid.

`dimensionAtObservation` has controller default `TIME_PERIOD`, so it becomes non-null on nearly
every request and changes the outbound URL for every existing 2.1 registry. It is a valid 2.1
parameter, so this is intended; E2E against OECD is the gate.

### Step 6: Add the `dataconstraint` and `+` rules to the 2.1 normalizer (P3, P10)

**Files:** `Sdmx21QueryNormalizer.java` and `Sdmx21QueryNormalizerImpl.java`
(both under `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/`)

Add `String toSdmx21StructureType(String structureType)` mapping `dataconstraint` to
`contentconstraint` and passing everything else through, and extend `toSdmx21PathSlot` to map `+` to
`latest`. Both idempotent.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java`

Apply the type rule in `getStructures21`, at the choke point that already normalizes the path slots.
`toSdmx21PathSlot` is shared by `getFlowRef`, so `P10` fixes the data and availability flow
references in the same edit.

`dataconstraint` must be listed in the registry's `supportedStructures` for the request to reach the
adapter at all; `checkStructureTypeIsSupported` runs first and matches on the 3.0 spelling.

### Step 7: Declare the accepted `references` per registry (P9)

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureEndpointConfiguration.java`

Add `Set<String> supportedReferences`, `Set<String> supportedReferencesForAll`, and
`Map<String, String> referencesDowngrade`. Null or empty `supportedReferences` disables the check,
so registries without the fields keep forwarding whatever the client sent.

**New:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/ReferencesResolver.java`

Picks the accepted set by scope (wildcard resource id uses `supportedReferencesForAll`, falling back
to `supportedReferences`), passes an accepted value through, rewrites through
`referencesDowngrade`, and otherwise throws `UnsupportedContextException`, which extends
`BadRequestException` and so answers 400.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

Resolve before the `references` assignment in **both** structure builders --
`translateStructureQuery` and `translateWildcardStructureFanOut`. Both forwarded `references`
verbatim through the same defect, and in the fan-out each registry carries its own accepted set.
Resolution happens inside the translator, before `CacheKeyGenerator` reads
`query.getReferences()`, so a downgraded response is never cached under the requested value.

The downgrade is per registry, not global. `datastructure -> descendants` is right for Eurostat
because `descendants` returns the DSD plus codelists and concept schemes. It is not right for a
registry that serves `datastructure` natively, which is why the map lives in configuration.

### Step 8: Flush the cache when the configuration changes (P8)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheService.java`

Add `void invalidateAll();`.

**File:** `InMemoryCacheService.java` -- invalidate all three Caffeine caches.

**File:** `RedisCacheService.java` -- delete by the three prefixes the class already declares, using
`SCAN` with a match pattern rather than `KEYS`, which blocks the server. A shared Redis holds other
tenants' data, so a blanket `FLUSHDB` is not acceptable.

**Files:** `ProxyConfigurationProviderImpl.java` and `ConfigServerConfigExtractor.java`

Swap the unconditional set for `getAndSet` plus an `Objects.equals` comparison, flushing only on an
actual change. The comparison is not optional: `pollForUpdate` runs every 30 seconds by default, so
an unconditional flush would make the cache useless in production. Inject the cache as
`ObjectProvider<CacheService>` to avoid a circular dependency.

### Step 9: Add the ESTAT registry configuration

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`

Add an `ESTAT` registry and agency, SDMX 3.0 only:

- `disableCompression: true` -- the `P1` opt-in.
- Structure: `XML_STRUCTURE_3_0_0`, `supportedStructures` including `dataconstraint`.
- `supportedReferences: ["none", "children", "descendants"]`,
  `supportedReferencesForAll: ["none"]`.
- `referencesDowngrade` must include `children -> none` and `descendants -> none` alongside
  `datastructure -> descendants`, `parents -> none`, `parentsandsiblings -> none`, and
  `all -> descendants`. The map is consulted only when the value is not accepted for the query's
  scope, so for a concrete artefact `children` and `descendants` are accepted and those two entries
  never fire; they exist for the wildcard scope, where Eurostat 2.1 answers 400 and Eurostat 3.0
  drops the references silently. **Without them a wildcard query with `references=descendants`
  answers 400 instead of downgrading**, which contradicts edge case 7.
- Data: `CSV_DATA_2_0_0`, `replaceEmptyDimensionsWithWildcard: true` (the whole of `E7`),
  `supportsLimit: false`.
- No `availabilityEndpointConfig`. Eurostat serves no availability on either version, and the
  parallel worktree owns that decision.

`categoryscheme` and `categorisation` are absent because Eurostat 3.0 answers `500` for both
(`E5`). `supportsLimit: false` is recorded for correctness, but `limit` still fails until the
parallel worktree lands `P6`.

**File:** `sdmx-proxy-config/README.md`

CLAUDE.md requires the schema tables to move with the model. Add rows for `disableCompression` under
`VersionSpecificRegistryConfiguration`, and `supportedReferences`,
`supportedReferencesForAll`, `referencesDowngrade` under `StructureEndpointConfiguration`.

### Step 10: E2E suite and the two framework fixes it uncovers

**New:** `sdmx-proxy-e2e/.../registry/estat/3_0/estat_3_0_registry_config.json` (derived from the
shipped registry entry) and `estat_3_0_test_config.json`, plus
`sdmx-proxy-e2e/.../registry/ESTAT_3_0_RegistryTestSuit.java` following `BIS_3_0_RegistryTestSuit`.

Eurostat is the first registry with neither an availability nor a limit configuration block, which
exposes two pre-existing defects in `BaseRegistryTestSuite`:

1. `getAvailabilityCases()` dereferenced a null `availabilityTestSuitConfiguration`, failing at
   suite initialization. The guard belongs at the call site in `setUp`, because allpairs4j rejects a
   build with fewer than two parameters so an empty builder cannot stand in.
2. JUnit 5.10.2 treats a `@ParameterizedTest` with zero arguments as an initialization error, and
   `allowZeroInvocations` only arrives in 5.13. `availabilityCases()` emits one all-null case that
   `testAvailabilityEndpoint` skips on via `Assumptions.assumeTrue`, so the suite reports a skip
   with a reason. `limitEmulationFormats()` has the same shape; for Eurostat it is satisfied by a
   `limitTestSuitConfiguration` whose empty `registryReturnFormats` falls back to the data default,
   after which the suite's own availability assumption skips the test.

## No changes required

These files need **no modification**:

- **`FeignConfig.java`** -- `baseOkHttpClient` is shared by every registry. `P1` is per registry.
- **`SdmxSourceConfig.java`** -- `sdmxMLStructureReaderFactory()` already returns the factory that
  reads SDMX-ML 2.0, 2.1 and 3.0.
- **`StreamingDataConversionService.java`** -- the `XML_DATA_3_0_0` gap is real but out of scope.
- **`FilterNormalizer.java`** and everything under **`services/limit/`** -- owned by the parallel
  limit-emulation worktree.
- **`AvailabilityEndpointConfiguration.java`** -- `P7` belongs with the availability work.
- **`QueryTranslatorImpl.replaceEmptyDimensionsWithWildcard`** -- already correct. `E7` is
  configuration.
- **`DataQuery30Api.java`** -- `P11` is a won't-fix.

## Files affected

| File | Change type | Description |
|------|-------------|-------------|
| `sdmx-proxy-config/.../VersionSpecificRegistryConfiguration.java` | Modified | `disableCompression` flag |
| `sdmx-proxy-config/.../StructureEndpointConfiguration.java` | Modified | Three `references` fields |
| `sdmx-proxy/.../registry/api/http/IdentityEncodingClient.java` | New | Pins `Accept-Encoding: identity` |
| `sdmx-proxy/.../registry/api/http/IdentityEncodingClientProvider.java` | New | Wraps per registry |
| `sdmx-proxy/.../registry/api/SdmxApiClientProviderImpl.java` | Modified | Chains the decorator |
| `sdmx-proxy/.../conversion/StreamingStructureConversionService.java` | Modified | `XML_STRUCTURE_3_0_0` parser arm |
| `sdmx-proxy/.../conversion/StreamingAvailabilityConversionService.java` | Modified | Same arm |
| `sdmx-proxy/.../adapter/GenericRegistryAdapterImpl.java` | Modified | `ISO_INSTANT`, 2.1 query params, structure-type rule |
| `sdmx-proxy/.../translator/Sdmx21QueryNormalizer.java` | Modified | `toSdmx21StructureType` |
| `sdmx-proxy/.../translator/Sdmx21QueryNormalizerImpl.java` | Modified | `dataconstraint` and `+` rules |
| `sdmx-proxy/.../translator/ReferencesResolver.java` | New | Scope-aware `references` clamp |
| `sdmx-proxy/.../translator/QueryTranslatorImpl.java` | Modified | Calls the resolver in both structure builders |
| `sdmx-proxy/.../services/cache/CacheService.java` | Modified | `invalidateAll()` |
| `sdmx-proxy/.../services/cache/InMemoryCacheService.java` | Modified | Implements it |
| `sdmx-proxy/.../services/cache/RedisCacheService.java` | Modified | Implements it with `SCAN` by prefix |
| `sdmx-proxy/.../configuration/ProxyConfigurationProviderImpl.java` | Modified | Flush on an actual change |
| `sdmx-proxy/.../extractor/ConfigServerConfigExtractor.java` | Modified | Same, on poll |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` | Modified | ESTAT registry and agency |
| `sdmx-proxy-config/README.md` | Modified | Schema table rows |
| `sdmx-proxy-e2e/.../framework/BaseRegistryTestSuite.java` | Modified | Availability guard and zero-case sentinel |
| `sdmx-proxy-e2e/.../registry/ESTAT_3_0_RegistryTestSuit.java` | New | E2E suite |
| `sdmx-proxy-e2e/.../registry/estat/3_0/*.json` | New | E2E configuration pair |

## Edge cases

1. **A registry compresses correctly.** `disableCompression` defaults to `false`, the provider
   returns the delegate unchanged, and the wire behavior for BIS, IMF, and OECD is byte-identical.
2. **`Accept-Encoding` set twice.** OkHttp adds its own header when none is present. The decorator
   removes both casings before putting, so exactly one `identity` reaches the registry.
3. **429 retry with compression disabled.** `RateLimitRetryClient` replays the `Request` it was
   given. The identity wrapper sits closer to OkHttp, so every replay is re-decorated.
4. **A 2.1 document arrives on a registry configured as `XML_STRUCTURE_3_0_0`.** The shared factory
   sniffs the namespace, so it reads correctly. The format enum selects the factory, not the parser
   version.
5. **A registry declares no `supportedReferences`.** The resolver returns the requested value
   unchanged, so every existing registry keeps its behavior.
6. **`references` outside the set with no downgrade entry.** The proxy answers 400 naming the
   accepted set, instead of forwarding a request that returns an opaque upstream fault.
7. **Wildcard detection for `supportedReferencesForAll`.** The resolver treats `null`, `""`, `*`,
   and `all` as wildcards. `QueryTranslator.normalizePathSlot` maps inbound `all` to `*` before the
   translator runs, so in practice only `*` is seen; both are matched so the resolver is correct
   wherever it is called from.
8. **Cache flush under load.** `invalidateAll` drops entries; in-flight requests already hold their
   streams. The next request repopulates. A configuration change is rare, so the cost is one cold
   period.
9. **Redis shared with other tenants.** Prefix-scoped `SCAN` and delete, never `FLUSHDB`.
10. **`updatedAfter` on the 2.1 path after both fixes.** `P5` makes the value formattable and `P4`
    forwards it. Eurostat ignores parameters it does not implement and returns the unfiltered
    result, so the response is correct but not narrowed. That is Eurostat's behavior, not a defect
    in this change.
11. **`E6`, deferred, facts recorded so the follow-up need not re-probe.** On 2.1, `+` inside a key
    position works and scales: `A.JAN.BE` 852 bytes, `A.JAN.BE+FR` 1,623, `A.JAN.BE+FR+DE` 2,392.
    On 3.0 both `A.JAN.BE+FR` and `A.JAN.BE,FR` return
    `400 INVALID_URL_VALUE: Invalid value '+' in key parameter`, and value sets live only in `c[]`:
    `c[geo]=BE` 746 bytes, `BE,FR` 1,409, `BE,FR,DE` 2,070. The proxy's existing translation
    already produces the accepted form on both versions -- `c[geo]=BE,FR` becomes the 2.1 key
    `..BE+FR` and stays `c[geo]=BE,FR` on 3.0 -- so the gap is only a client that writes a value
    set directly into the key path and hits a 3.0 registry.

## Verification

### Unit tests

| File | Coverage |
|------|----------|
| `StreamingStructureConversionServiceTest` | `shouldParseSdmxMl30StructureDocument`, `shouldConvertSdmxMl30StructureDocumentToJson` -- the only evidence for the sdmx-core claim, so write them first |
| `IdentityEncodingClientTest` | Header set, both casings replaced, other headers and URL preserved |
| `IdentityEncodingClientProviderTest` | Delegate returned when the flag is false, wrapped when true |
| `Sdmx21QueryNormalizerImplTest` | `dataconstraint` mapping, other types passed through, `+` to `latest`, all idempotent |
| `ReferencesResolverTest` | Accepted pass-through, `datastructure` downgrade, scope selection, wildcard downgrade, rejection without an entry, pass-through when unconfigured |
| `GenericRegistryAdapterImplTest` | All eight 2.1 parameters forwarded, nulls omitted, `detail` folding, `+` to `latest` in the flow reference |
| `InMemoryCacheServiceTest` | All three caches dropped |
| `ProxyConfigurationProviderImplTest` | One flush on change, none when unchanged, none when the override stays null |

### Manual verification

Start the proxy on a free port with the test config endpoint enabled. ESTAT ships in
`sdmx_registries_config.json`, so no `POST /config` is needed for the 3.0 checks:

```bash
SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true ./gradlew :sdmx-proxy:bootRun \
  --args='--server.port=8063 --sdmxproxy.test.config-endpoint.enabled=true'
```

Each request names the defect it covers and the result observed before the fix:

```bash
P=http://localhost:8063/statgpt/sdmx-proxy/api/v0
J="Accept: application/vnd.sdmx.structure+json;version=2.0.0"
D="Accept: application/vnd.sdmx.data+json;version=2.0.0"

# P1 + P2: was 500, expect 200
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -H "$J" \
  "$P/sdmx/3.0/structure/dataflow/ESTAT/NAMA_10_GDP/+"

# P9: was 400, expect 200 (downgraded to descendants, ~2.5 MB)
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -H "$J" \
  "$P/sdmx/3.0/structure/dataflow/ESTAT/NAMA_10_GDP/+?references=datastructure"

# P9 wildcard: expect 200 and references dropped, not silently ignored
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -H "$J" \
  "$P/sdmx/3.0/structure/dataflow/ESTAT/*/*?references=descendants"

# E7: was 500 DIMENSION_FILTER_SPEC_INVALID, expect 200
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -H "$D" \
  "$P/sdmx/3.0/data/dataflow/ESTAT/TPS00001/+/A..BE"

# P5: was 500 UnsupportedTemporalTypeException, expect 200
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" -H "$D" \
  "$P/sdmx/3.0/data/dataflow/ESTAT/TPS00001/+/*?updatedAfter=2024-01-01T00:00:00Z"
```

Check the outbound URLs in the proxy log rather than the status codes alone: `references=descendants`
for the downgrade, `references=none` for the wildcard, `A.*.BE` for `E7`,
`updatedAfter=2024-01-01T00:00:00Z` for `P5`, and no `filename="*.gz"` anywhere for `P1`.

`P3`, `P4` and `P10` are 2.1-only and need a 2.1 ESTAT configuration pushed to `POST /config`.
Expect `contentconstraint` outbound, a smaller body for `lastNObservations=1`, and
`ESTAT,TPS00001,latest` rather than `ESTAT,TPS00001,+`.

`P8` needs no restart: push one configuration, fetch a dataflow, push another, fetch the same
dataflow, and confirm the second call goes out rather than returning the previous body in under
15 ms.

### E2E tests

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.ESTAT_3_0_RegistryTestSuit" "-Dtest.ignoreFailures=true"
```

Steps 4, 5 and 8 change behaviour for every registry, so the regression suites are the gate rather
than a formality. Run them too:

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.OECD_2_1_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
```

`OECD_2_1_RegistryTestSuit` is the only SDMX 2.1 suite in the repository, so it alone gates the 2.1
data-path change. `IMF_2_1_RegistryTestSuit` is named in the CLAUDE.md run commands but does not
exist; only `IMF_3_0` is present.

Recorded result on 2026-09-04: 874 tests, 0 failures, 107 skips across the four suites (ESTAT 120,
BIS 400, IMF 266, OECD 88), 884 proxy requests, zero proxy-side errors.

## SDMX standard references

- SDMX-REST data resource, query parameters: `sdmx-rest-2.2.0/doc/data.md`
- SDMX-REST structure resource, `references` and `detail`: `sdmx-rest-2.2.0/doc/structures.md`
- sdmx-core structure reader dispatch:
  `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/factory/structure/SdmxMLStructureReaderFactory.java`
- sdmx-core SDMX-ML 3.0 structure reader:
  `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/engine/structure/reader/v3/StaxStructureReaderEngineV3.java`
- sdmx-core namespace constants (`STRUCTURE_NS_3_0`, `namespacesV3`):
  `sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/constants/SdmxConstants.java`
- sdmx-core schema detection:
  `sdmx-core-2.3.9/fusion-utils-sdmx/src/main/java/io/sdmx/utils/sdmx/structure/SdmxMessageUtil.java`

## Open questions

1. **`toSdmx21Detail` mapping fidelity.** The three narrowing combinations map cleanly, but
   `attributes` in SDMX 3.0 accepts more than `dsd`, `all`, and `none` (for example a
   comma-separated attribute list), which 2.1 `detail` cannot express. The mapping treats anything
   other than `none` as full detail, which over-fetches rather than under-fetches. Confirm that is
   the wanted direction.
2. **`dimensionAtObservation` on existing 2.1 registries.** OECD passed 88 tests with the parameter
   now being sent, which answers the original concern. Revisit if another 2.1 registry is added.

## Review Log
