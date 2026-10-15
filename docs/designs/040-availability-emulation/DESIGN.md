# Design: Availability emulation for registries that do not serve `availableconstraint`

> **Status: implemented.** Four deviations from the design as written, all simplifications found
> while building it:
>
> 1. **Both paths share one renderer.** The constraint path was going to convert the registry's
>    constraint document; instead `UnfilteredConstraintSource` reduces it to the same
>    `HarvestedCoverage` the probe produces, and both go through
>    `AvailabilityConstraintSynthesizer`. The `TwoSourceParityTest` this design asked for is
>    therefore true by construction rather than by assertion, and the two planned availability
>    fixtures (`DROP_TIME_PERIOD_KEY_VALUE`, `KEEP_FIRST_CUBE_REGION`) were not needed -- dropping
>    the time dimension and reducing to one region happen while reading the coverage.
> 2. **No JSON probe harvester.** SDMX-CSV and SDMX-ML are harvestable; SDMX-JSON is not. Both
>    target registries serve CSV, and the SDMX-JSON series shapes differ enough between 1.0, 2.0
>    and what individual registries emit that implementing it without a captured fixture would be
>    guesswork. `ConfigValidator` and `SeriesKeyHarvesterProvider` both reject an unharvestable
>    `probeFormat` by name. Listed under Deferred.
> 3. **`CacheKeyGenerator` takes the canonicalizer as a parameter.** It is a static utility and
>    the canonicalizer is an injectable component; passing it in keeps one implementation of
>    "is this narrowed" rather than duplicating the predicate statically.
> 4. **The 2.1 data path forwards only `detail`.** `firstNObservations` / `lastNObservations` are
>    still dropped there (the pre-existing `//TODO support other query params` at
>    `GenericRegistryAdapterImpl:123`). Forwarding them would change existing OECD data requests,
>    which is out of scope for this design.

## Context

Eurostat is being onboarded as an SDMX 2.1 registry (`ESTAT`). It serves structures and data, but
it does not implement the availability endpoint at all:

| Request (probed live 2026-09-04) | Result |
|----------------------------------|--------|
| `GET /sdmx/2.1/availableconstraint/NAMA_10_GDP/ALL/ALL?references=none` | **405**, `Allow: OPTIONS`, 18-byte `text/html` |
| `GET /sdmx/3.0/availability/dataflow/ESTAT/PRC_HICP_MINR/1.0/*/*` | **404**, 9-byte `text/html` |
| `GET /sdmx/2.1/dataflow/ESTAT/NAMA_10_GDP` | 200 |
| `GET /sdmx/3.0/structure/dataflow/ESTAT/PRC_HICP_MINR` | 200 |

Neither status is the spec's `501`, and the two API versions disagree with each other. The ESTAT
registry fragment prepared by the backend team already records this as
`availabilityEndpointConfig.availabilityEnabled: false` (see
`statgpt-backend/docs/migrations/eurostat-proxy/estat-registry.json`).

That flag is a dead letter today. `availabilityEnabled` is declared on
`AvailabilityEndpointConfiguration` (`AvailabilityEndpointConfiguration.java:15`), documented in
`sdmx-proxy-config/README.md:87` as "If false, availability requests are rejected", and **read by
nothing in `sdmx-proxy/src/main`** -- only by an e2e assumption
(`BaseRegistryTestSuite.java:1070`). So an ESTAT availability request is forwarded upstream and
the raw `405` is passed through to the client.

The consumer does not survive that. `statgpt-backend` fans availability out across all candidate
datasets with `gather_with_concurrency`, which takes no `return_exceptions`, so one Eurostat
dataflow fails the whole turn including unrelated IMF and BIS datasets. The full analysis is
`statgpt-backend/docs/migrations/eurostat-proxy/BUG-eurostat-availability.md`.

This design gives `availabilityEnabled: false` a meaning: when availability is off for a
registry, the proxy answers the request itself from a cheaper upstream source.

## Two sources, split on the filter

The backend issues availability in two distinct shapes, and they want different upstreams.

| Request shape | Source | Cost |
|---------------|--------|------|
| **No narrowing at all** -- absent/`all`/`*`/all-wildcard key, no filters | The registry's `Actual` content constraint | 5.6--40 KB, ~0.3 s |
| **Any narrowing** -- a partial key or any `c[]`/body filter | A series-key **data probe** carrying that exact narrowing | 44--52 KB typical |

### Why the content constraint is the right answer for the unfiltered request

Eurostat does not implement `availableconstraint`, but it does implement the constraint
*structure* resource, and for the empty key it answers **exactly the question availability is
asking** -- not an approximation of it:

```
GET /sdmx/2.1/contentconstraint/ESTAT/NAMA_10_GDP     -> 200, 5 597 bytes, 0.27 s
```

```xml
<s:ContentConstraint agencyID="ESTAT" id="NAMA_10_GDP" version="1.0" type="Actual" ...>
  <s:ConstraintAttachment>
    <Ref agencyID="ESTAT" class="Dataflow" id="NAMA_10_GDP" package="datastructure" version="1.0"/>
  </s:ConstraintAttachment>
  <s:CubeRegion include="true">
    <c:KeyValue id="freq">        <!-- 1 value:  A -->
    <c:KeyValue id="unit">        <!-- 32 values -->
    <c:KeyValue id="na_item">     <!-- 39 values -->
    <c:KeyValue id="geo">         <!-- 46 values -->
    <c:KeyValue id="TIME_PERIOD"> <!-- 51 discrete values, 1975 .. 2025 -->
```

`type="Actual"` and the `geo` count settle that this is real coverage and not the DSD's allowed
set: the `ESTAT:GEO` codelist has 4 292 codes, the constraint lists 46.

**It is measurably identical to what the probe would return.** Comparing the constraint against a
harvest of the whole-cube `serieskeysonly` probe, dimension by dimension:

| Dataflow | Probe series | Constraint vs probe |
|----------|--------------|---------------------|
| `NAMA_10_GDP` | 35 383 | `freq` 1/1, `unit` 32/32, `na_item` 39/39, `geo` 46/46 -- **0 divergence either way** |
| `PRC_HICP_MINR` | 100 141 | `freq` 1/1, `unit` 5/5, `coicop18` 555/555, `geo` 46/46 -- **0 divergence either way** |

So this is not a trade of accuracy for cost. It is the same answer, and it replaces the single
worst request in the system: the unfiltered probe runs 1.8--139 MB and 1--71 s (measured below),
the constraint fetch runs 5.6--40 KB and 0.3 s.

### The hard rule

**The constraint source is consulted only when the request carries no narrowing whatsoever. A
narrowed request is never answered from it -- not as a strategy, not as a fallback, not on probe
failure.**

This is worth stating as a rule rather than leaving to judgment, because the constraint is
dataflow-scoped and silently ignores narrowing:

```
GET /contentconstraint/ESTAT/NAMA_10_GDP/1.0?c[geo]=EL             -> 200, 5 597 B (byte-identical)
GET /contentconstraint/ESTAT/NAMA_10_GDP/1.0/A.CLV05_MEUR.B1GQ.EL  -> 405
```

Returning it for a filtered request would hand the consumer the full cube dressed up as a
narrowed one. Formally a superset, so nothing would crash -- and that is the danger. It would
silently destroy every narrowing decision downstream: filter search candidates
(`hybrid_searcher.py:416`), auto-fill of missing dimensions
(`query_constructor/iterative.py:106-156`), the strategy rollover that triggers on an empty
availability (`query_constructor/composite.py:70`), and the whole indicator-combination index.
Silently wrong narrowing is worse than a loud failure, so a filtered request that cannot be
probed fails with a `5xx`.

"No narrowing" is decided by the same canonicalization the cache key uses (see "Caching"): an
absent, `all`, `*`, or all-wildcard positional key **and** an absent or empty filter map.

### Both unfiltered call sites are real, and one of them is uncached

The unfiltered shape is not a rare load-time event, and it is not confined to the SDMX 2.1
connector -- `PROXY_SDMX30` inherits the SDMX 2.1 implementation rather than replacing it
(`StatGptSdmxProxyDataSet(UpdatedAtMixin, Sdmx21DataSet)`,
`StatGptSdmxProxyDataSourceHandler(Sdmx21DataSourceHandler)`).

1. **Dataset load** -- `DataflowLoader._load_constraints` (`sdmx/v21/dataflow_loader.py:96`) calls
   `availableconstraint` with no `key` and no `params`, `use_cache=True`. The proxy connector
   reuses that loader explicitly: `statgpt_sdmx_proxy/v30/datasource.py:66-67` overrides
   `_create_dataflow_loader` to return the same `DataflowLoader`, and `:104` calls
   `load_structure_message(urn, mode="full")`, in which `_load_constraints` is unconditional.
   (`mode="shallow"` skips it, but line 104 is the only `load_structure_message` call in the proxy
   datasource and it is always `"full"`.) This is the response `dimensions_creator.py:226-249`
   requires to contain a key value for every coded dimension.
2. **Indicator index construction** -- `_load_indicator_combinations_to`
   (`sdmx/v21/dataset.py:634-635`) builds an explicitly empty
   `DataSetAvailabilityQuery(dimensions_queries_dict={})`, which `availability_query`
   (`:1183-1197`) turns into `key=None, params=None` with **`use_cache=False`**. A live,
   client-uncached, unfiltered request, issued to order the indicator dimensions by cardinality
   before the recursive walk begins.

Call site 2 is why the unfiltered source must be cheap on the proxy side even with caching: it
bypasses the backend's own availability cache entirely, so it recurs on every index build.

## The probe

### Mechanism

For a narrowed request: one data request, **same key and same filters** as the availability
request, asking for series keys and nothing else.

```
SDMX 2.1:  GET {dataUrl}/{flowRef}/{key}?detail=serieskeysonly
           Accept: application/vnd.sdmx.data+csv;version=1.0.0

SDMX 3.0:  GET {dataUrl}/{context}/{agency}/{id}/{version}/{key}?attributes=none&measures=none
           Accept: application/vnd.sdmx.data+csv;version=2.0.0
```

`detail` exists only in SDMX-REST 1.5.0; SDMX-REST 2.x replaces it with `attributes` +
`measures`. Both API versions return **identical key sets** -- cross-checked by counting series
from `2.1 startPeriod=endPeriod=P&detail=serieskeysonly` against
`3.0 c[TIME_PERIOD]=P&measures=none`: 2 089 keys each at `P=1980`, 35 356 keys each at `P=2020`.
So the version choice is purely about payload cost, and since ESTAT is configured `SDMX_2_1` the
probe stays on 2.1. No registry re-registration is needed to ship this.

**SDMX-CSV is the probe format**, for three reasons:

1. Measured **3.36x smaller than SDMX-ML on an identical result set** -- the same 35 356 keys
   (`TIME_PERIOD=2020`) come back as 6 495 624 bytes of `serieskeysonly` SDMX-ML versus
   1 933 799 bytes of `csvdata&attributes=none&measures=none`.
2. The server has already done the deduplication. Dropping the observations drops the
   `TIME_PERIOD` column, so **every row is a distinct series key** -- no client-side dedup.
3. The header names the dimensions, so the harvester needs no DSD lookup to map columns.

```
DATAFLOW,LAST UPDATE,freq,unit,na_item,geo
ESTAT:NAMA_10_GDP(1.0),03/09/26 23:00:00,A,CLV05_MEUR,B1G,EL
ESTAT:NAMA_10_GDP(1.0),03/09/26 23:00:00,A,CLV05_MEUR,B1GQ,EL
```

`CSV_DATA_1_0_0` is already in ESTAT's `dataEndpointConfig.supportedFormats`.

### `lastNObservations` is the wrong knob, and `serieskeysonly` is the right one

The originally proposed mechanism was `lastNObservations=1`. It does not work, for a reason worth
recording because it is counter-intuitive.

Eurostat gates data requests on an **estimated extraction cost in cells, computed from the
requested cube before `detail` or `firstN`/`lastN` are applied**: under 500 k cells synchronous,
500 k -- 5 M asynchronous, above 5 M rejected. Probed on `ESTAT:PRC_HICP_MINR`, whole dataflow:

```
?lastNObservations=1                        -> 413  EXTRACTION_TOO_BIG
?detail=nodata                              -> 413  EXTRACTION_TOO_BIG
?detail=serieskeysonly&lastNObservations=1   -> 413  EXTRACTION_TOO_BIG
   faultstring: "estimated 46975200 rows, max authorised is 5000000"
```

So `lastNObservations=1` makes a broad request *worse*: it collapses the time coverage to a
single period **and** re-enters the cell estimator, which rejects it. Eurostat's own API FAQ says
as much -- `lastNObservations` without a time filter is costly because "the full data need to be
extracted".

**`detail=serieskeysonly` on its own is not gated at all.** It appears to be served from a key
index. Probed on the whole dataflow with no key -- the worst case the probe path can face, and the
case the content-constraint source now removes:

| Dataflow | Series | Payload | Time |
|----------|--------|---------|------|
| `DEMO_R_MWK_TS` | 116 | 6 379 B | 0.2 s |
| `PRC_HICP_MIDX` | ~26 k | 1 794 636 B | 3.3 s |
| `NAMA_10_GDP` | 35 383 | 2 253 667 B | 1.2 s |
| `AVIA_PAOC` | ~30 k | 2 176 317 B | 1.2 s |
| `PRC_HICP_MINR` | 100 141 | 6 365 625 B | 2.8 s |
| `DEMO_R_MAGEC` | ~140 k | 9 435 365 B | 4.0 s |
| `NRG_CB_OIL` | ~300 k | 20 530 205 B | 10.4 s |
| `HLTH_CD_ACDR2` | 723 485 | 49 441 711 B | 30.4 s |
| `EF_LSK_MAIN` | -- | 52 645 389 B | 21.4 s |
| `BD_9BD_SZ_CL_R2` | 2 067 919 | 139 610 759 B | 71.0 s |

Every one returned **HTTP 200 synchronously** -- no `413`, no async queue, including the 139 MB
one. So the probe works for arbitrarily broad keys; the constraint is bandwidth and latency, not
a server-side cap. Narrowed probes, which is what the probe path actually serves, are three
orders of magnitude smaller: partial key `geo=EL` on `NAMA_10_GDP` is 51 972 B on 2.1 CSV,
44 603 B on 3.0 CSV, 819 series, ~0.2 s.

### Why payload size does not become memory pressure

The harvest is a per-dimension value set, bounded by the DSD rather than by the number of series:

| Dataflow | Payload | Series | Harvested coverage |
|----------|---------|--------|--------------------|
| `BD_9BD_SZ_CL_R2` | 139 610 759 B | 2 067 919 | `freq=1, indic_sb=87, sizeclas=5, nace_r2=194, geo=40` -- **327 strings** |
| `HLTH_CD_ACDR2` | 49 441 711 B | 723 485 | `freq=1, unit=1, sex=3, age=33, icd10=93, geo=500` -- **631 strings** |

139 MB of transfer collapses to 327 strings. The harvester **streams and never materializes the
key set**: memory is `O(sum |A_d|)`, and the emulated response is a few KB regardless of probe
size. This is why the guardrails below are expressed in bytes transferred and seconds rather than
in heap.

### Key decomposition, for latency not for the gate

Since `serieskeysonly` is not gated, decomposition is a latency and resilience tool, not a
correctness requirement. When a probe exceeds `probeSplitThresholdBytes` or its own timeout:

1. Pick the dimension with the largest codelist among those the client did **not** constrain
   (cardinalities from the DSD via the existing `CodelistSizeResolver` in `services.limit`).
2. Chunk that dimension's values into `probeSplitChunkSize` groups.
3. Issue one probe per chunk with the chunk pinned into the key/filter, up to `maxProbeFanOut`.
4. **Union** the per-dimension harvests.

Union-decomposition is exactly correct, not an approximation. For every dimension other than the
split dimension, the union across chunks equals the unsplit result; for the split dimension, the
union of chunk values that returned rows equals the unsplit result. A cube region is a
rectangular projection anyway -- it carries no joint structure that splitting could lose, which is
equally true of a real availability response.

With the constraint source handling the unfiltered case, decomposition should rarely trigger; it
exists for a narrowed-but-still-broad key on a very large dataflow.

### The asynchronous `200`

Eurostat may answer a data request with HTTP 200 whose body is a SOAP envelope rather than data:

```xml
<env:Envelope ...><env:Body><ns0:syncResponse ...>
  <processingTime>111</processingTime>
  <queued><id>a801f1c5-...</id><status>SUBMITTED</status></queued>
</ns0:syncResponse></env:Body></env:Envelope>
```

381 bytes, carrying the `Content-Type` of the *requested* format -- so nothing but the body
distinguishes it. Observed on a cold `PRC_HICP_MINR?detail=serieskeysonly` in SDMX-ML; the same
URL re-issued after the job finished returned the real 6 767 146-byte payload.

This must be detected. Feeding the envelope to a CSV harvester yields an empty coverage map,
which the backend reads as "nothing available" and which **silently drops the dataset** from the
candidate set. The harvester sniffs the leading bytes; a body that is not the requested format
and contains `syncResponse`/`queued` raises `AvailabilityProbeQueuedException`, and the emulator
retries the same URL on the `asyncRetry` schedule. On budget exhaustion the request fails `503`.

## The TIME_PERIOD question

The content constraint *does* carry `TIME_PERIOD` -- 51 discrete values for `NAMA_10_GDP`, 368 for
monthly `PRC_HICP_MINR`, 1 388 for weekly `DEMO_R_MWK_TS`. A series-key probe carries none:
`serieskeysonly` and `nodata` both return zero observations by construction, and `TIME_PERIOD` is
the observation dimension (verified: byte-identical 1 130-byte responses for both on
`NAMA_10_GDP/A.CLV05_MEUR.B1GQ.EL`).

**Decision: neither path emits a `TIME_PERIOD` key value.** Four reasons, and the fourth is the
one that arises specifically from having two sources:

1. **The consumer cannot receive time coverage on this path.** On `PROXY_SDMX30` the backend
   models a key value as `ProxyKeyValue.values: list[{value: str}]`
   (`statgpt-backend/statgpt/common/data/statgpt_sdmx_proxy/sdmx_schemas/structure_message.py`) --
   there is **no representation for a range**, so `time_period_start`/`time_period_end` are
   structurally always `None` on the proxy path, and both consumers of those bounds degrade to
   permissive no-ops when they are `None`.
2. **An enumerated list is payload with no consumer.** It would parse, then land as an ordinary
   `IN` dimension query over hundreds of values.
3. **It matches the registries the backend is already tested against.** Neither IMF nor BIS
   returns a `TIME_PERIOD` key value today (verified in this repo's own availability fixtures).
4. **It would make the two sources structurally different.** The unfiltered answer would carry a
   `TIME_PERIOD` key value and every filtered answer would not, so `available_values` at load
   time would contain a dimension that query-time narrowing never mentions. Dropping it on both
   paths keeps the responses shape-identical, which is what makes the subset invariant below
   checkable rather than special-cased.

`includeTimePeriod` remains as a config flag, defaulting to `false`. Note that switching it on
would only be coherent once the probe path can also produce time coverage -- see "Deferred".

## Consumer contract

Read out of `statgpt-backend`. The hard requirements are hard because violating them raises
rather than degrades; this is the acceptance criteria.

1. **Exactly one `dataConstraint`, containing exactly one `cubeRegion`.** Two of either raises
   `ValueError` (`sdmx/v21/dataset.py:1106`, `:1111`; `dimensions_creator.py:228`, `:232`). Zero
   constraints is tolerated at query time but **raises at load time**
   (`dimensions_creator.py:228` tests `!= 1`, not `>= 2`).
2. **A key value for every coded dimension of the DSD** on the unfiltered request. A missing one
   raises `Missing dimension({id}) value in data content constraint`
   (`dimensions_creator.py:238-239`), the dataset goes `offline`, and it drops out of every index
   and every query. The synthesizer therefore enumerates the DSD's dimensions and emits a key
   value for each, empty if unobserved -- the probe's CSV header is not the authority on which
   dimensions must appear.
3. **Exact code strings.** They are dict keys in three places that raise bare `KeyError`
   (`sdmx/v21/dataset.py:737`, `query/incomplete_queries.py:87`) or silently drop candidates
   (`hybrid_searcher.py:416`).
4. **A filtered response must be a subset of the unfiltered one.** A code returned under a filter
   but absent from load-time `available_values` is a `KeyError` at `incomplete_queries.py:87`.
   With two sources this is the one invariant that needs active protection -- see below.
5. **Required JSON fields.** Per constraint: `id`, `name`, `version`, `agencyID`, `cubeRegions`.
   Per cube region: `include`. Per key value: `id`, `include`, `removePrefix`, and
   `values[].value`. Omitting `removePrefix` is a pydantic `ValidationError`. The existing
   conversion tail emits all of these for IMF and BIS today; a parity test pins it.

**Can be omitted:** the `series_count` annotation (read by nobody), `constraintAttachment`,
`role`, `links`, `type`, `validFrom`/`validTo`, `meta` (not modelled). Constraint `id`, `version`
and `agencyID` must be *present* and the `id` unique, but no value is ever inspected. Value
ordering is irrelevant -- the backend sorts.

**Failure must be loud.** Returning an empty constraint on failure is worse than the current hard
error: at load time it raises anyway, and at query time it means "nothing available", which
silently drops the dataset. Every failure is a `5xx`; nothing fabricates an empty or partial cube.

### Protecting the subset invariant across the two sources

The unfiltered answer comes from the constraint resource and the filtered answers come from data
probes. Measured, they agree exactly (see the comparison table above) -- but they are two
different upstream resources with potentially different refresh cadences, so a probe could in
principle return a code the constraint does not list, and that direction is a bare `KeyError` in
the backend.

**The emulator therefore intersects every probe harvest with the constraint universe**, per
dimension, preserving the universe's order:

```
A_d = [ v for v in universe[d] if v in harvested[d] ]
```

The universe is the same artefact the unfiltered path serves, already fetched and cached, so this
costs nothing. It makes the filtered answer consistent with whatever the load-time answer
actually was, which is precisely what requirement 4 demands -- consistency with the reported
universe matters more than absolute freshness, because a code the load-time response omitted has
no entry in `available_values` for the backend to look up anyway.

A non-empty intersection difference is logged at `WARN` with both counts. On the evidence above it
should never fire; if it starts firing, the two resources have diverged and that is worth
knowing.

When no constraint resource is configured (`unfilteredSource: PROBE`), both paths use probes and
the invariant holds by construction, with no intersection needed.

## Solution overview

1. **Production gate on `availabilityEnabled`.** `AdapterRouterImpl.getAvailability` branches
   before the bypass check. `availabilityEnabled: true` keeps today's behaviour byte-for-byte.
2. **`emulation` block on `AvailabilityEndpointConfiguration`.** `type: NONE` (the default)
   rejects with `501` -- the spec's code for an unimplemented method, and strictly better than
   proxying a `405`.
3. **`AvailabilityEmulator` + `AvailabilityEmulatorProvider`**, resolving by type from the Spring
   bean list -- the same shape as `SeriesLimitTruncatorProvider`.
4. **`DataQueryAvailabilityEmulator`** -- routes on "is this request narrowed?", serves the
   unfiltered case from `UnfilteredConstraintSource`, and probes otherwise.
5. **`UnfilteredConstraintSource`** -- fetches the registry's `Actual` constraint once, cached,
   and serves it in both roles: the unfiltered response, and the intersection universe.
6. **A cache domain for emulated availability**, because there is none today: `CacheService`
   caches parsed structures, ready structure responses and the limit-emulation shrink result, and
   `AdapterRouterImpl.getAvailability` never touches `cacheService` at all.

### The unfiltered path

The 2.1 structure client already passes the structure type through verbatim
(`Sdmx21StructureClient.getStructures`,
`GET /{structureType}/{agency}/{id}/{version}?references=&detail=`), so no new client is needed:

```
GET {structureEndpointConfig.url}contentconstraint/{agencyID}/{resourceID}/{version}
    ?references=none&detail=full
Accept: application/vnd.sdmx.structure+xml;version=2.1
```

Eurostat is parameter-tolerant here: bare, `/1.0`, `/all`, `?references=none`, `?detail=full` all
return the same 200.

Two configuration consequences:

- `contentconstraint` must be added to ESTAT's `structureEndpointConfig.supportedStructures`, or
  `QueryTranslatorImpl.checkStructureTypeIsSupported` (`:395-407`) rejects the internal query.
- The resource is `contentconstraint` in SDMX 2.1 and `dataconstraint` in SDMX 3.0, and the proxy
  performs **no** 3.0 -> 2.1 structure-type renaming today. The name is resolved from the target
  registry's `sdmxVersion` via `emulation.constraintStructureType`.

The response is an SDMX-ML 2.1 structure message whose only artefact is a `ContentConstraint`.
`SdmxBeans` already exposes `getContentConstraintBeans()` / `getActualContentConstraintBeans()`,
`SdmxMLStructureReaderFactory` already parses it, and `ContentConstraintMapper`
(`common/mapping/ContentConstraintMapper.java:56-71`) already maps `ContentConstraintBean` ->
`com.epam.jsdmx.infomodel.sdmx30.DataConstraint` for the SDMX-JSON 2.0.0 writer. So the unfiltered
path reuses
`StreamingAvailabilityConversionService.convert(in, out, XML_STRUCTURE_2_1, targetMediaType)`
unchanged, with response shaping done as **availability fixtures**, which is where the repo
already puts per-registry response patches:

- `DROP_TIME_PERIOD_KEY_VALUE` -- unless `includeTimePeriod: true`. New `AvailabilityFixtureType`.
- `KEEP_FIRST_CUBE_REGION` -- defensive, enforces requirement 1. New `AvailabilityFixtureType`.

### Harvesting

`SeriesKeyHarvester` mirrors `SeriesLimitTruncator`: `Set<SdmxFormat> supportedFormats()` and a
streaming `harvest(InputStream, SdmxBeans)`, accumulating one `LinkedHashSet<String>` per
dimension plus a row counter.

```java
public record HarvestedCoverage(
        Map<String, Set<String>> valuesByDimensionId,
        long seriesCount) {}
```

`seriesCount` is exact here, unlike the estimate the limit-emulation bisect works from. Merging
two harvests (decomposition) is a per-dimension set union plus a sum.

### Synthesizing the filtered response

The probe path has no upstream constraint document to convert, so it builds the artefact directly
in `com.epam.jsdmx.infomodel.sdmx30` and writes it with the same `JsonWriterFactory`. Verified
available on `sdmx30-infomodel:2.0.0`:

```java
var region = new CubeRegionImpl();
region.setIncluded(true);
region.setCubeRegionKeys(keys);            // one CubeRegionKeyImpl per DSD dimension
                                           //   setComponentId / setIncluded(true)
                                           //   setRemovePrefix(false)   <- backend requires it
                                           //   setSelectionValues(List<MemberValueImpl>)
var constraint = new DataConstraintImpl();
constraint.setConstraintRoleType(ConstraintRoleType.ACTUAL);
constraint.setConstrainedArtefacts(List.of(dataflowReference));
constraint.setCubeRegions(List.of(region));

var artefacts = new ArtefactsImpl();
artefacts.setDataConstraints(Set.of(constraint));
```

Going direct rather than through the `io.sdmx` bean layer is deliberate: `ContentConstraintMapper`
renders a time range via `SdmxDate` -> `Date` -> `Instant.toString()`
(`ContentConstraintMapper.java:205-210`), turning `2020-Q1` into `2020-01-01T00:00:00Z`.
`TimeRangePeriodImpl.setPeriod(String)` takes the raw string, so the infomodel layer keeps SDMX
reporting periods intact. Only relevant if `includeTimePeriod` is ever switched on, but it costs
nothing to pick the lossless path now.

`StreamingAvailabilityConversionService` gains a `write(Artefacts, OutputStream, MediaType)`
overload, extracted from the existing `processAsJson` (`:90-121`), so both paths share one writer.
A parity test asserts the two paths emit the same JSON shape for the same coverage -- the property
that keeps requirement 4 checkable.

## Guardrails

All per registry, all fail loud:

- **`maxProbeBytes`** (default 256 MiB -- measured worst case is 139 MB) -- a counting wrapper on
  the probe stream, aborting with `ResponseTooLargeException`
  (`exception/ResponseTooLargeException.java`, `500`). Note the existing
  `InMemoryReadableDataLocationFactory` cap does **not** cover this path, because the harvester
  streams and never builds a `ReadableDataLocation`.
- **`maxProbeSeries`** (default 5 000 000) -- row cap on the harvester, same exception.
- **`probeTimeoutMillis`** -- separate from the registry's general `readTimeout`, since a broad
  probe legitimately runs longer than an ordinary data request should.
- **`maxProbeFanOut`** (default 16) -- decomposition budget. Exhausted -> `503`.
- **The registry's own `resilienceConfig`** -- the probe is an ordinary outbound data call, so the
  circuit breaker, retry and rate limiter already apply.

`emulation.type: NONE` answers `501`, because there the proxy knows statically that it cannot
serve the request. Everything else that fails answers `5xx`.

## Caching

- New `CacheService` domain: `getEmulatedAvailability(String)` /
  `putEmulatedAvailability(String, byte[])`, in both `InMemoryCacheService` and
  `RedisCacheService`.
- `CacheKeyGenerator.generateAvailabilityEmulationKey(TranslatedAvailabilityQuery)`, prefix
  `avail_emu:`, following `generateLimitEmulationKey` (`CacheKeyGenerator.java:151-179`):
  `avail_emu:{registry}:{agency}:{resource}:{version}:{md5(canonicalKey + sortedFilters + componentId + mode + includeTimePeriod + mediaType)}`.
- **Canonicalization is load-bearing, not cosmetic.** The two unfiltered call sites arrive in
  different shapes -- an absent key on the GET route versus `{"filters": []}` on the POST route --
  and must land on one entry. The same canonicalization decides "is this request narrowed", so
  routing and caching cannot disagree about it: an absent, `all`, `*`, or all-wildcard positional
  key collapses to one wildcard token, and absent and empty filter maps collapse to the same
  empty map.
- TTL under `sdmxproxy.cache.ttl.availabilityEmulation`, default `PT6H` with `PT30M` jitter --
  ready-response-like rather than structure-like, since coverage moves when data is published.
- The cached artefact is the **rendered response bytes** (a few KB), not the probe payload. The
  probe response is never cached and never buffered.
- The constraint fetch is additionally cached by the existing structure cache, since it is an
  ordinary structure query -- so the universe used for intersection is shared across all filtered
  requests for a dataflow.

Proxy-side caching is load-bearing because the backend caches only its *unfiltered* availability
call and explicitly forbids caching a filtered one
(`statgpt_sdmx_proxy/v30/sdmx_client.py:237-239` raises if `use_cache` is combined with a key or
params), so **every query-time narrowing step is a live round trip**.

## Interaction with limit emulation

Design 014's shrink loop uses `/availability` as its *cheap* probe (`AdapterRouterImpl.java:470`,
`genericRegistryAdapter::getAvailability`). With filtered availability emulated by a data probe,
the cheap probe is no longer cheap, and the loop's probe budget (default 8) becomes up to 8 data
probes per request. Worse, the loop's probes are always *narrowed* -- so they land on the probe
path every time, never the constraint fast path.

Two facts keep this from biting today, and one guard makes it safe:

- ESTAT is configured `supportsLimit: true` -- Eurostat accepts `limit` with a `200` and ignores
  it -- so limit emulation does not run for it.
- `LimitEmulationServiceImpl` builds its own `TranslatedAvailabilityQuery` (`:616-645`,
  `:653-679`) and calls the adapter directly, bypassing the router's emulation branch entirely,
  so it would currently receive the raw `405`.

**Guard:** `ConfigValidator` rejects `dataEndpointConfig.supportsLimit: false` together with
`availabilityEndpointConfig.availabilityEnabled: false` on the same version, naming both flags.

Worth noting for that future design: the probe returns an **exact** `seriesCount`, better than the
`series_count` annotation the bisect estimates from -- so the combination is solvable, just not
implicitly.

## Architecture

### 1. Config module (`sdmx-proxy-config/`)

```
configuration/data/AvailabilityEndpointConfiguration.java   (modified)
    + private AvailabilityEmulationConfiguration emulation;

configuration/data/availability/AvailabilityEmulationType.java           (new enum)
    NONE, DATA_QUERY

configuration/data/availability/UnfilteredAvailabilitySource.java        (new enum)
    CONTENT_CONSTRAINT, PROBE

configuration/data/availability/AvailabilityEmulationConfiguration.java  (new)
    AvailabilityEmulationType type = NONE;
    UnfilteredAvailabilitySource unfilteredSource = CONTENT_CONSTRAINT;
    String     constraintStructureType;             // default per sdmxVersion
    SdmxFormat probeFormat;                         // default: cheapest CSV in supportedFormats
    String     probeDetail = "serieskeysonly";         // SDMX 2.1 only
    boolean    includeTimePeriod = false;
    long       maxProbeBytes  = 268_435_456L;
    long       maxProbeSeries = 5_000_000L;
    int        probeTimeoutMillis = 180_000;
    long       probeSplitThresholdBytes = 33_554_432L;
    int        probeSplitChunkSize = 8;
    int        maxProbeFanOut = 16;
    AsyncRetryConfig asyncRetry;                    // enabled, maxAttempts 4, initial 2000ms,
                                                    //   multiplier 2.0, maxTotalWaitMillis 30000

configuration/data/fixture/AvailabilityFixtureType.java     (modified)
    + DROP_TIME_PERIOD_KEY_VALUE, KEEP_FIRST_CUBE_REGION
```

`ConfigValidator` additions:

- `emulation.type != NONE` requires `availabilityEnabled: false`.
- `unfilteredSource == CONTENT_CONSTRAINT` requires the resolved `constraintStructureType` to be
  present in `structureEndpointConfig.supportedStructures`. Without it the internal query is
  rejected at translation time and surfaces as an opaque `500`.
- `probeFormat`, when set, must be in `dataEndpointConfig.supportedFormats`.
- `probeDetail` must not be set on an `SDMX_3_0` version, where `detail` does not exist.
- The `supportsLimit: false` + `availabilityEnabled: false` rejection above.

### 2. Main module (`sdmx-proxy/`) -- new package `services.availability`

| Class | Role |
|-------|------|
| `AvailabilityEmulator` (iface) | `AvailabilityEmulationType supportedType()`; `InputStream emulate(TranslatedAvailabilityQuery, SdmxBeans, AvailabilityEmulationContext)` |
| `AvailabilityEmulatorProvider` | resolves by type from the bean list; mirrors `SeriesLimitTruncatorProvider` |
| `AvailabilityEmulationContext` | `fetchStructure` + `fetchData` callbacks, so no emulator depends on `GenericRegistryAdapter`. Same rule as `AvailabilityProber` |
| `DataQueryAvailabilityEmulator` | routes narrowed vs unfiltered; orchestrates probe -> harvest -> intersect -> synthesize |
| `AvailabilityQueryCanonicalizer` | the single definition of "narrowed"; shared with `CacheKeyGenerator` |
| `UnfilteredConstraintSource` | fetches + caches the `Actual` constraint; serves the unfiltered response and the intersection universe |
| `ProbeQueryBuilder` | `TranslatedAvailabilityQuery` -> `TranslatedDataQuery`; branches on `SdmxVersion` for `detail` vs `attributes`/`measures`; carries key and filters through unchanged |
| `ProbeDecomposer` | picks the split dimension via `CodelistSizeResolver`, chunks it, merges harvests by union |
| `AvailabilityConstraintSynthesizer` | `HarvestedCoverage` + DSD + dataflow ref -> `Artefacts`; a key value per DSD dimension, empty where unobserved |
| `harvest/SeriesKeyHarvester` (iface) | `Set<SdmxFormat> supportedFormats()`; `HarvestedCoverage harvest(InputStream, SdmxBeans)` |
| `harvest/CsvSeriesKeyHarvester` | `CSV_DATA_1_0_0`, `CSV_DATA_2_0_0` |
| `harvest/XmlSeriesKeyHarvester` | 2.1 generic + structure-specific, StAX |
| `harvest/JsonSeriesKeyHarvester` | `JSON_DATA_1_0_0`, `JSON_DATA_2_0_0` |
| `harvest/SeriesKeyHarvesterProvider` | resolves by format |
| `harvest/QueuedResponseDetector` | sniffs the SOAP `queued` envelope in a `200` |

Modified:

- `services/adapter/conversion/StreamingAvailabilityConversionService` -- extract
  `write(Artefacts, OutputStream, MediaType)`.
- `services/adapter/AdapterRouterImpl.getAvailability` -- the gate and the cache.
- `common/data/TranslatedDataQuery` -- `+ String detail`.
- `services/adapter/GenericRegistryAdapterImpl.getData21` -- forward `detail` (today it builds a
  generic `Map<String, Object>` carrying only `startPeriod`/`endPeriod`, behind a
  `//TODO support other query params` at `:123`, so this is one map entry).
- `services/cache/*` -- the new domain.
- `services/fixture/availability/*` -- the two new fixtures.

New exceptions, slotting into the design-020 hierarchy:

- `AvailabilityNotSupportedException extends NotImplementedException` -> `501`
- `AvailabilityEmulationException extends ServerErrorException` -> `500`
- `AvailabilityProbeQueuedException extends ServiceUnavailableException` -> `503`

### 3. `AdapterRouterImpl.getAvailability` -- the gate

Inserted ahead of `convertKeyToFilters` and the bypass check (`:509-532`):

```
availabilityConfig = versionConfig.getAvailabilityEndpointConfig()
if availabilityConfig.isAvailabilityEnabled():
    <today's path, unchanged>

emulation = availabilityConfig.getEmulation()
if emulation == null or emulation.type == NONE:
    throw AvailabilityNotSupportedException(registry, sdmxVersion)   // 501

cacheKey = CacheKeyGenerator.generateAvailabilityEmulationKey(query)
cached = cacheService.getEmulatedAvailability(cacheKey)
if cached.isPresent():
    return out -> out.write(cached.get())

beans    = getSdmxBeans(getStructureQuery(query))     // already cached
emulator = emulatorProvider.forType(emulation.type)
bytes    = drain(emulator.emulate(query, beans, context))
cacheService.putEmulatedAvailability(cacheKey, bytes)
return out -> out.write(bytes)
```

And inside `DataQueryAvailabilityEmulator`:

```
if canonicalizer.isUnfiltered(query) and emulation.unfilteredSource == CONTENT_CONSTRAINT:
    return unfilteredConstraintSource.render(query, beans)      // 5.6-40 KB, ~0.3 s

coverage = probe(query, beans)                                   // decompose if oversized
if emulation.unfilteredSource == CONTENT_CONSTRAINT:
    coverage = intersect(coverage, unfilteredConstraintSource.universe(query, beans))
return synthesizer.render(coverage, beans, dataflowRef)
```

Bypass is skipped on the emulated path by construction: there is no upstream availability
response to pass through, and the payload is always written by our own writer. Buffering the
*rendered response* is deliberate -- it is a few KB and has to be materialized to be cached
anyway. The *probe* is never buffered.

The rule that only `AdapterRouter` talks to `GenericRegistryAdapter`
(`AvailabilityProber.java:13-15`) is preserved: emulators reach upstream only through
`AvailabilityEmulationContext`.

### 4. Registry configuration

ESTAT, extending the fragment the backend team prepared:

```jsonc
"structureEndpointConfig": {
  "supportedStructures": [
    "datastructure", "conceptscheme", "codelist", "dataflow", "categoryscheme",
    "contentconstraint"                       // added: the unfiltered source
  ]
},
"dataEndpointConfig": {
  "supportedFormats": ["XML_GENERIC_DATA_2_1", "XML_STRUCTURE_SPECIFIC_DATA_2_1", "CSV_DATA_1_0_0"],
  "defaultFormat": "XML_GENERIC_DATA_2_1",
  "supportsLimit": true
},
"availabilityEndpointConfig": {
  "url": "https://ec.europa.eu/eurostat/api/dissemination/sdmx/2.1/",
  "supportedFormats": ["XML_STRUCTURE_2_1"],
  "defaultFormat": "XML_STRUCTURE_2_1",
  "bypassEnabled": false,
  "availabilityEnabled": false,
  "emulation": {
    "type": "DATA_QUERY",
    "unfilteredSource": "CONTENT_CONSTRAINT",
    "constraintStructureType": "contentconstraint",
    "probeFormat": "CSV_DATA_1_0_0",
    "probeDetail": "serieskeysonly",
    "includeTimePeriod": false,
    "probeTimeoutMillis": 180000,
    "probeSplitThresholdBytes": 33554432,
    "maxProbeFanOut": 16
  },
  "fixtures": [
    { "type": "DROP_TIME_PERIOD_KEY_VALUE", "config": {} }
  ]
}
```

`availabilityEndpointConfig.url` is now unused for ESTAT -- nothing is sent to it. It stays because
`SdmxApiClientProviderImpl` and `determineAvailabilityReturnFormat` both dereference the block,
and `defaultFormat` still drives format negotiation.

## Edge cases

| Case | Behaviour |
|------|-----------|
| `availabilityEnabled: true` | Today's path, unchanged. No emulation code runs. |
| `availabilityEnabled: false`, no `emulation` block | `501 AvailabilityNotSupportedException`. |
| Unfiltered request, `unfilteredSource: CONTENT_CONSTRAINT` | Constraint fetch, 5.6--40 KB. Both backend unfiltered call sites hit one cache entry. |
| Unfiltered request, `unfilteredSource: PROBE` | Wildcard-key probe: 1.8--139 MB, streamed and cached. For registries with no constraint resource. |
| Narrowed request, probe fails for any reason | `5xx`. **Never** falls back to the constraint -- that would silently report the full cube as narrowed. |
| Constraint returns two constraints or two cube regions | `KEEP_FIRST_CUBE_REGION` fixture reduces to one. |
| Constraint omits a coded dimension | `500`. Silently omitting it takes the dataset offline in the backend; failing names the dimension. |
| Probe returns a code the constraint universe lacks | Dropped by the intersection, `WARN` with both counts. Measured divergence today is zero. |
| Probe observes nothing for a dimension | Key value present with an empty `values` list. Correct availability semantics, and it does not raise. |
| Probe returns zero rows | Every key value empty. The backend reads it as "no data for this filter". |
| Queued `200` | Retry the same URL per `asyncRetry`; `503` on exhaustion. Never parsed as data. |
| Probe exceeds `probeSplitThresholdBytes` or its timeout | Decompose on the largest unconstrained dimension, union the harvests, up to `maxProbeFanOut`. |
| `413 EXTRACTION_TOO_BIG` | Treated as oversized: decompose, then `503`. Not expected for `serieskeysonly`. |
| Client asks for XML availability | `UnsupportedConversionException` (`501`), same as the non-emulated path. |
| `mode=available` | Accepted, answered as `exact`. Logged at `DEBUG`. No consumer sends it. |
| `componentId` naming one dimension | The full cube region is returned -- a superset of what was asked. The backend never sends `componentId`. |
| SDMX 3.0 registry | `attributes=none&measures=none`, no `detail`; `dataconstraint` as the constraint type. Same code path. |

## Verification

### Unit tests

- `AvailabilityQueryCanonicalizerTest` -- the routing decision. Absent key, `all`, `*`,
  all-wildcard positional key, absent filters and empty filter map are all "unfiltered"; any
  partial key or any filter is "narrowed". This single predicate decides both routing and cache
  identity, so it is pinned first.
- `UnfilteredConstraintSourceTest` -- the real 5 597-byte Eurostat `contentconstraint` response as
  a fixture, through the conversion tail, asserting every field requirement 5 lists and one
  constraint with one cube region.
- `CsvSeriesKeyHarvesterTest` -- the real 819-series `NAMA_10_GDP/A...EL` SDMX-CSV response; per-dimension
  value sets, `seriesCount == 819`, no `TIME_PERIOD` key. A second fixture pins the `Σ|A_d|`
  property on a slice of the 2 067 919-row `BD_9BD_SZ_CL_R2` probe.
- `XmlSeriesKeyHarvesterTest` / `JsonSeriesKeyHarvesterTest` -- the same cube in the other
  formats; a cross-harvester parity test asserts all three agree.
- `QueuedResponseDetectorTest` -- the captured 381-byte SOAP envelope raises; a real CSV body and
  a real SDMX-ML body do not.
- `ProbeQueryBuilderTest` -- key and filters carried through **unchanged**; 2.1 emits
  `detail=serieskeysonly` and no `attributes`/`measures`; 3.0 emits `attributes=none&measures=none`
  and no `detail`; neither emits `limit`, `firstNObservations` or `lastNObservations`.
- `ProbeDecomposerTest` -- split dimension excludes client-constrained dimensions; chunking covers
  every value once; union merge equals the unsplit harvest on a fixture pair; fan-out exhaustion
  raises.
- `IntersectionTest` -- probe-only codes dropped with a `WARN`; universe-only codes retained as
  absent from the filtered answer; order follows the universe.
- `AvailabilityConstraintSynthesizerTest` -- golden SDMX-JSON 2.0.0 output; requirement 5 field by
  field including `removePrefix`; a key value for every DSD dimension including unobserved ones.
- **`TwoSourceParityTest`** -- the same coverage rendered through the constraint path and the
  synthesizer path produces the same JSON shape. This is what makes requirement 4 checkable
  rather than special-cased, and it is the test that catches the two paths drifting apart.
- `AdapterRouterImplTest` -- `availabilityEnabled: true` never calls the provider; `false` +
  `NONE` throws `501`; `false` + `DATA_QUERY` caches on the second call.
- `ConfigValidatorTest` -- each new rejection.
- `CacheKeyGeneratorTest` -- distinct keys for differing filters, `componentId` and media type;
  and the canonicalization pin: absent key with no filters, `all` key, `*` key, all-wildcard
  positional key and empty filter map all produce **one** key, so the two unfiltered backend call
  sites share a single cache entry.

### E2E tests

Per project convention, cross-registry pins go in `BaseRegistryTestSuite` behind a toggleable
config block, never as `@Test` methods on one registry's suite. New
`AvailabilityEmulationTestSuitConfiguration` (dataflow cases, filtered/unfiltered key pairs,
whether a differential check is available), consumed by new generic cases:

- `testEmulatedAvailabilityShapeIsValid` -- one constraint, one cube region, a key value for every
  non-time DSD dimension, and every field the backend requires. Run for both request shapes.
- **`testEmulatedAvailabilityNarrowsUnderFilter`** -- the test this design exists for. For a
  dataflow with a known-narrowing key, the filtered response must be a strict subset of the
  unfiltered one on at least one dimension. A filtered request answered from the constraint fails
  here, which is exactly the regression worth guarding.
- `testEmulatedAvailabilityIsSubsetOfUnfiltered` -- requirement 4, on every dimension.
- `testUnfilteredSourcesAgree` -- with `unfilteredSource: CONTENT_CONSTRAINT` and then `PROBE`,
  the unfiltered response must be identical. This is the assertion that the measured
  zero-divergence above keeps holding; if Eurostat's constraint ever starts lagging its data,
  this fails and tells us.
- `testEmulationRejectedWhenTypeIsNone` -- `501`.

**The differential test against a real registry is the one that earns its keep.** IMF serves
availability natively. Configure IMF twice against the same dataflow and the same filter -- once
`availabilityEnabled: true` (ground truth), once `availabilityEnabled: false` with
`DATA_QUERY` -- and assert the emulated cube region equals the real one per dimension, for both a
wildcard key and a narrowed key. That validates the emulation against a registry's own notion of
availability, which is the only way to know it is right rather than merely well-formed. The suite
already pushes config into the running proxy (`ProxyConfigPusher.push`,
`updateAvailabilityConfigToMatchRegistryReturnType`, `:986-1007`).

Note OECD is SDMX 2.1 and rate-limited to roughly 20 requests/minute; keep it out of the
probe-heavy and fan-out cases.

### Manual verification

```bash
B=https://ec.europa.eu/eurostat/api/dissemination/sdmx/2.1
CSV='application/vnd.sdmx.data+csv;version=1.0.0'

# 1. The gap this design closes (405 today)
curl -s -o /dev/null -w '%{http_code}\n' "$B/availableconstraint/NAMA_10_GDP/ALL/ALL?references=none"

# 2. The unfiltered source: real coverage, 5.6 KB, 0.27 s
curl -s -w '\n%{http_code} %{size_download} bytes %{time_total}s\n' \
  "$B/contentconstraint/ESTAT/NAMA_10_GDP"

# 3. Proof it cannot serve a narrowed request (byte-identical, then 405)
curl -s -o /dev/null -w '%{size_download}\n' "$B/contentconstraint/ESTAT/NAMA_10_GDP/1.0?c%5Bgeo%5D=EL"
curl -s -o /dev/null -w '%{http_code}\n'     "$B/contentconstraint/ESTAT/NAMA_10_GDP/1.0/A.CLV05_MEUR.B1GQ.EL"

# 4. The filtered probe: 819 distinct series keys, no TIME_PERIOD column, ~52 KB
curl -s -w '\n%{size_download} bytes\n' -H "Accept: $CSV" \
  "$B/data/NAMA_10_GDP/A...EL?detail=serieskeysonly" | head -3

# 5. The unfiltered probe the constraint source replaces -- 139 MB, 71 s, still HTTP 200 sync
curl -s -o /dev/null -w '%{http_code} %{size_download} bytes %{time_total}s\n' -H "Accept: $CSV" \
  "$B/data/BD_9BD_SZ_CL_R2?detail=serieskeysonly"

# 6. Why lastNObservations is not the answer (413 EXTRACTION_TOO_BIG)
curl -s "$B/data/PRC_HICP_MINR?lastNObservations=1" | head -c 200

# 7. Through the proxy, once implemented -- filtered must narrow relative to unfiltered
P="$SDMX_PROXY_HOST/statgpt/sdmx-proxy/api/v0/sdmx/3.0/availability/dataflow/ESTAT/NAMA_10_GDP/1.0"
curl -s -H 'Accept: application/vnd.sdmx.structure+json;version=2.0.0' "$P/*/all?mode=exact"
curl -s -H 'Accept: application/vnd.sdmx.structure+json;version=2.0.0' "$P/A.*.*.EL/all?mode=exact"
```

## Non-goals

- **`mode=available` semantics.** The backend never sends `mode` -- nor `componentId`, nor
  `references` -- on either route, so there is no consumer to satisfy. The emulator answers as
  `mode=exact`. Supporting `available` means dropping the filter of the component under
  inspection, which for `componentId=*` costs one probe per constrained dimension.
- **`references` expansion.** The emulated response carries the constraint only.
- **XML and SDMX 2.1 availability output.** `StreamingAvailabilityConversionService` already
  throws `UnsupportedConversionException` for both (`:72-76`, `:112-116`). Both emulation paths
  share the writer and inherit the limitation. The backend asks for
  `application/vnd.sdmx.structure+json;version=2.0.0` and nothing else.
- **Runtime capability sniffing.** The trigger is configuration, not a `405`/`404` observed at
  request time. Eurostat returns two different wrong codes for its two API versions and OECD
  returns `500` for an unrelated bad parameter. If a one-shot probe at registry-registration time
  is ever built, note that the response *shape* discriminates where the status does not: Eurostat
  answers a malformed query with a 351-byte SDMX `<S:Fault>` but an absent endpoint with an
  18-byte `text/html` body.
- **Polling Eurostat's asynchronous extraction API.** A queued probe is retried at the same URL,
  which is what makes the payload appear; `/1.0/async/` is not driven.
- **Fixing the backend's fan-out.** `gather_with_concurrency` without `return_exceptions` is a
  backend bug and stays one. Emulation reduces how often it fires; it does not remove it.

## Deferred

- **`mode=available`.** One extra probe per constrained dimension. Add when a consumer sends it.
- **`TIME_PERIOD` coverage under a filter.** `startPeriod=endPeriod=<period>` (2.1) or
  `c[TIME_PERIOD]=<period>` (3.0) does genuinely narrow the key set -- probed on `NAMA_10_GDP`:
  `1980` -> 2 089 keys, `2020` -> 35 356 -- so per-period coverage is recoverable at
  `O(periods)` probes. Both API versions validate the period against actual coverage (`1975` and
  `2025` succeed, `1960` and `2026` return `400`), and that window matches the dataflow's
  `OBS_PERIOD_OVERALL_OLDEST`/`LATEST` annotations, so the annotation range can bound the
  candidate periods first. Tolerable for annual, not for a 1 388-period weekly flow. Only worth
  building once a consumer can receive it, and it is the prerequisite for turning
  `includeTimePeriod` on -- otherwise the two paths would report time inconsistently.
- **An SDMX-JSON probe harvester.** SDMX-CSV and SDMX-ML are harvestable today. SDMX-JSON is not:
  the series shape differs between SDMX-JSON 1.0 (an object keyed by index tuples) and 2.0, and
  between registries within 2.0, so implementing it without a captured fixture from the registry
  in question would be guesswork. Both target registries serve CSV, and both
  `ConfigValidator` and `SeriesKeyHarvesterProvider` reject an unharvestable `probeFormat` by
  name, so the gap fails loudly at config load rather than at request time.
- **Concurrent decomposition.** Chunks are independent; running them in parallel under the
  registry rate limiter would cut tail latency substantially.
- **Registry capability probing at registration time.** More robust than hand-written config, and
  the config server is the natural place for it.
- **A probe feeding limit emulation an exact `seriesCount`.** Blocked behind the `ConfigValidator`
  rejection until designed properly.

## References

**SDMX specification**

- [`sdmx-rest/doc/data.md`](https://github.com/sdmx-twg/sdmx-rest/blob/master/doc/data.md) --
  data query parameters. `detail` (`full`/`dataonly`/`serieskeysonly`/`nodata`) exists in
  SDMX-REST 1.5.0 only; SDMX-REST 2.x replaces it with `attributes` + `measures`, and
  `startPeriod`/`endPeriod` with `c[TIME_PERIOD]`.
- [`sdmx-rest/doc/availability.md`](https://github.com/sdmx-twg/sdmx-rest/blob/master/doc/availability.md) --
  the time dimension is returned as a range, not an enumeration.
- [`sdmx-rest/doc/status.md`](https://github.com/sdmx-twg/sdmx-rest/blob/master/doc/status.md) --
  `501` is the canonical code for an unimplemented method; `413` for an oversized response. There
  is no capability-discovery mechanism in SDMX-REST.

**Eurostat**

- [Asynchronous API](https://ec.europa.eu/eurostat/web/user-guides/data-browser/api-data-access/api-detailed-guidelines/asynchronous-api) --
  the 500 k / 5 M cell thresholds and `EXTRACTION_TOO_BIG`.
- [API FAQ](https://ec.europa.eu/eurostat/web/user-guides/data-browser/api-data-access/api-faq) --
  `lastNObservations` requires the full extraction.
- [SDMX 2.1 data query](https://ec.europa.eu/eurostat/web/user-guides/data-browser/api-data-access/api-detailed-guidelines/sdmx2-1/data-query)
  and [SDMX 3.0 data query](https://ec.europa.eu/eurostat/web/user-guides/data-browser/api-data-access/api-detailed-guidelines/sdmx3-0/data-query).
- WADLs -- authoritative for which resources exist, incomplete for parameters:
  [2.1](https://ec.europa.eu/eurostat/api/dissemination/sdmx/2.1/sdmx-rest.wadl),
  [3.0](https://ec.europa.eu/eurostat/api/dissemination/sdmx/3.0/sdmx-rest.wadl).
  Both list `contentconstraint` / `dataconstraint`; neither lists `availableconstraint` or
  `availability`.

**In-repo**

- `docs/designs/014-heuristic-limit-emulation/` -- the emulation pattern this design follows, and
  the availability-as-cheap-probe assumption it must not break.
- `docs/designs/039-sdmx-21-request-translation-429-retry/` -- SDMX 2.1 request normalization.
- `docs/designs/020-exception-hierarchy-refactor/` -- where the new exceptions slot in.
- `sdmx-proxy-config/README.md` -- the configuration table the new block must be documented in.

**In `statgpt-backend`**

- `docs/migrations/eurostat-proxy/BUG-eurostat-availability.md` -- the consumer-side failure.
- `docs/migrations/eurostat-proxy/estat-registry.json` -- the ESTAT registry fragment this design
  extends.
