# Design: Statistics Canada registry integration

Status: Draft
Author: design-feature skill
Related: 040 (availability emulation), 041 (Eurostat integration: `references` clamping, 2.1
request translation), 016 (`convertKeyToFilters`), 014 (limit emulation)

## Context

StatGPT needs Statistics Canada (StatCan) as a data source. StatCan publishes an SDMX REST service
that identifies itself as "NSI Web Service v7.8.0.0" and speaks SDMX 2.1 only:

- SDMX base URL: `https://www150.statcan.gc.ca/t1/wds/sdmx/statcan/rest/`
- User guide: `https://www.statcan.gc.ca/en/developers/sdmx/user-guide`
- Web Data Service (WDS), a separate non-SDMX JSON API on the same host:
  `https://www150.statcan.gc.ca/t1/wds/rest/`, documented at
  `https://www.statcan.gc.ca/en/developers/wds/user-guide`

The request came with four reported StatCan defects, numbered `R1`..`R4` here. Live probes taken on
2026-10-26 confirmed all four and found six more blockers, numbered `B1`..`B6`. Without a fix for
`B1` and `B2`, every request to StatCan fails, so they are in scope even though nobody reported
them.

StatCan identifiers follow one convention. Table (cube) `17100005` is published as dataflow
`StatCan:DF_17100005(1.0)` with DSD `StatCan:Data_Structure_17100005(1.0)`. WDS calls the table
number `productId`. All 8,274 WDS product IDs are 8 digits long.

### Scope

The user decided each item as follows:

| ID | Defect | Decision |
|----|--------|----------|
| `R1` | DSD components and concepts without a representation | New XML 2.1 structure fixture. A concept gets its component's representation; anything with neither gets `String` |
| `R2` | The series key is mandatory | Expand a match-all key into an OR over the codes of the dimension with the smallest codelist. Test long URLs |
| `R3` | SDMX-JSON series keys use `.` instead of `:` | Never request StatCan JSON. Pin the `:` separator in the proxy's own JSON output with E2E |
| `R4` | No `FREQ` dimension | No proxy change. Check how StatGPT behaves without `FREQ`; synthesize it later only if StatGPT breaks |
| `B1` | Only `GET /structure/{dsdId}` exists | Emulate the structure resources StatGPT calls, from the per-table bundle |
| `B2` | No dataflow list | Build `dataflow/StatCan/all` from WDS `getAllCubesListLite`, current tables only |
| `B3` | Codelist and concept-scheme URNs collide across tables | Rename them per table in the proxy |
| `B4` | The data `flowRef` accepts a bare dataflow ID only, and the path must end after the key | Two data endpoint flags |
| `B5` | No availability endpoint and no content constraint | Design 040 emulation through data probes, for the unfiltered request too |
| `B6` | HTTP 409 while a table is being updated | Retry 409 for this registry |
| `B7` | Every empty result is malformed XML | New streaming data fixture, applied to data and to the availability probe |
| `B8` | The 2.1 availability probe drops the time filter | Derive `startPeriod` and `endPeriod` for emulated 2.1 availability |

Out of scope:

- The WDS `vector` resource, and every WDS method other than `getAllCubesListLite`.
- `datastructure/StatCan/all`, `codelist/StatCan/all`, `conceptscheme/StatCan/all`. Serving them
  means downloading thousands of bundles. StatGPT never asks for them, so the proxy answers
  `501`.
- A category scheme built from the WDS `subjectCode`. StatGPT reads a category scheme only when the
  channel is configured with a category-scheme hierarchy.
- Splitting an over-long OR key into several requests. See edge case 3.

## Problem

### Evidence from the live probes

The probes listed in the following table ran against the SDMX base URL. The 2.1 `references` and
`detail` parameters were tried on every structure path and changed nothing.

| Request | Status | Note |
|---------|-------:|------|
| `structure/Data_Structure_17100005` | `200` | 89,858 bytes. Agency scheme, dataflow, DSD, 10 codelists, one concept scheme |
| `datastructure/StatCan/Data_Structure_17100005/latest` | `404` | Also `/1.0`, without version, and agency `all` |
| `dataflow/all/all/latest`, `dataflow/StatCan`, `dataflow/StatCan/DF_17100005/1.0` | `404` | |
| `codelist/StatCan/all/latest`, `conceptscheme/StatCan/all/latest` | `404` | |
| `structure/DF_17100005`, `structure/CONCEPTS_17100005` | `404` | Only a DSD ID works |
| `data/DF_17100005/1.1.1` | `200` | |
| `data/StatCan,DF_17100005/1.1.1`, `data/StatCan,DF_17100005,1.0/1.1.1`, `data/all,DF_17100005,latest/1.1.1` | `404` | The user guide documents these forms |
| `data/DF_17100005/1.1.1/all`, `data/DF_17100005/1.1.1/StatCan` | `404` | Any provider segment fails, with either XML 2.1 `Accept` value |
| `data/DF_17100005/1.1.1` with `Accept` structure-specific or generic XML 2.1 | `200` | 4,013 and 6,934 bytes, `Content-Type` matches the request |
| `data/DF_17100005/all` | `400` | `{"message":"Syntax error"}` |
| `data/DF_17100005/..` and `data/DF_17100005/` | `404` | |
| `data/DF_17100005/.1.`, `data/DF_17100005/..1` | `200` | One non-empty position is enough |
| `data/DF_17100005/.1.` | `200` | 354,498 bytes: one `Geography` |
| `data/DF_17100005/.1+2+3.` | `200` | The whole dataflow: 14,012,593 bytes in 14 seconds |
| `availableconstraint/DF_17100005`, `availableconstraint/DF_17100005/1.1.1` | `404` | |
| Data with `Accept: application/vnd.sdmx.data+json;version=1.0.0` or `text/csv` | `406` | |
| Data with `Accept: application/vnd.sdmx.data+json;version=1.0.0-wd` | `200` | |
| Data with `detail=serieskeysonly` or `detail=dataonly` | `200` | Ignored: same 6,934 bytes as `full` |
| Data with `lastNObservations`, `startPeriod`, `endPeriod`, `dimensionAtObservation` | `200` | Honored |
| Data with an 8,462-character URL | `414` | A 7,462-character URL returns `200` |
| `data/DF_17100005/.2+3+1.?startPeriod=1800&endPeriod=1801`, `data/DF_17100005/999.1.1` | `200` | No data. The body is malformed: `<message:DataSet ...></Series> </message:DataSet>`, in both XML 2.1 formats (`</generic:Series>` in generic) |

### R1: missing representations

In `Data_Structure_17100005`, `<str:PrimaryMeasure id="OBS_VALUE">` has no `LocalRepresentation`,
and none of the 14 concepts in `StatCan:CONCEPTS(1.0)` has a `CoreRepresentation`. The
`TIME_PERIOD` dimension and the `VECTOR_ID` and `NB_DECIMAL` attributes carry an empty
`<str:TextFormat/>`. The same holds for `Data_Structure_18100004`.

The proxy doesn't fail on this. sdmx-core turns an empty `TextFormat` into a null representation
(`sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/base/RepresentationBeanImpl.java`,
`getInstance`), and the writers skip null. But the client receives a DSD in which `OBS_VALUE` has
no type. The JSON 2.0 output carries `"coreRepresentation": {}` for every concept, because the
jsdmx concept writer opens the object before its null check. That is the defect the reporters
describe: validators can't check the data type.

No fixture fixes this. Every structure fixture accepts `JSON_STRUCTURE_2_0_0` input only, and
StatCan serves XML 2.1.

### R2: mandatory series key

A client that wants the whole dataflow sends no key and no `c[]` filter. The proxy sends `all`,
because `Sdmx21QueryNormalizerImpl.toKey` maps `null`, `""`, `*`, `all`, and any key whose
positions are all wildcards to `all`. StatCan answers `400`. An all-empty key such as `..` gets
`404`, and the normalizer can't produce it anyway.

A key with at least one non-empty position works. An OR over every code of one dimension selects
the same series as the match-all key, so it returns the whole dataflow.

### R3: JSON series keys

StatCan's SDMX-JSON uses `"0.0.0"` as the series key. The SDMX-JSON format requires `"0:0:0"`. The
upstream reader `SdmxJsonDataReaderEngine`
(`sdmx-core-2.3.9/fusion-sdmx-json/src/main/java/io/sdmx/format/json/engine/data/reader/SdmxJsonDataReaderEngine.java`,
line 479) splits on `:` and then calls `Integer.parseInt` on `"0.0.0"`, which throws
`NumberFormatException`.

StatCan's JSON is also wrong in ways a key fix can't repair:

- Monthly table `18100004` returns `TIME_PERIOD` values `2026-08` and `2026-09` in XML. In JSON,
  both become `{"id":"2027","start":"2026-08-01T00:00:00","end":"2027-07-31T23:59:59"}`.
- Observation values are JSON strings (`"21962032"`), not numbers.
- The media type is `version=1.0.0-wd`. The `SdmxFormat` enum can't express it, so the proxy
  can't request it.

The proxy therefore never requests StatCan JSON. The defect matters to the proxy's clients only if
the proxy's own JSON output uses the wrong separator, and E2E pins that it doesn't.

### R4: no FREQ dimension

Neither probed DSD has a frequency dimension. Nothing in `sdmx-proxy/src/main` depends on `FREQ`:
the string appears only in comments. StatGPT has a configurable `FREQUENCY` dimension subtype per
dataset (`statgpt-backend`, migration `2025_12_11_1116-5fac4226f0af`). Whether a dataset without it
works end to end is an open question that only a StatGPT run can answer.

WDS `getAllCubesListLite` returns a `frequencyCode` for every table: `12` (annual) for 17100005
and `6` (monthly) for 18100004. If StatGPT turns out to need `FREQ`, that is the source to use,
and a follow-up design maps it. 2,970 tables are `18` (occasional), which has no `CL_FREQ` code.

### B1 and B2: structure resources

StatGPT asks the proxy for the following structures
(`statgpt-backend/statgpt/common/data/sdmx/v21/datasource.py` and `dataflow_loader.py`):

| StatGPT call | Proxy request | Purpose |
|--------------|---------------|---------|
| `list_datasets` | `structure/dataflow/{agency or all}/all/latest?references=datastructure` | Dataset catalog |
| `_list_providers_via_dataflows` | `structure/dataflow/all/all/latest` | Provider discovery, one of two modes |
| `_load_dataflow` | `structure/dataflow/StatCan/DF_X/{version}?references=datastructure` | One dataset |
| `_load_concept_schemes` | `structure/conceptscheme/StatCan/{id}/{version}`, one per scheme | Concept names |
| `_load_code_lists` | `structure/codelist/StatCan/{id}/{version}`, one per codelist | Code names |
| `_load_constraints` | `POST availability/dataflow/StatCan/DF_X/{version}` with a JSON filter body | Available codes |

StatGPT turns a `400` or `404` from the availability call into an empty structure message
(`statgpt_sdmx_proxy/v30/sdmx_client.py`), so a failing probe shows up only as missing constraints
and a "Bad request for URL" log line.

The proxy serves `agencyscheme` itself from its configuration (`AgencySchemeService`), so the other
provider discovery mode needs no StatCan request.

The proxy sends every structure request through `Sdmx21StructureClient`, whose path template is
`/{structureType}/{agency}/{id}/{version}`. StatCan answers `404` to all of them. The data path is
blocked as well, because `AdapterRouterImpl.getData` first resolves the DSD through
`dataflow/{agency}/{id}/{version}?references=descendants`.

For the catalog there is no SDMX source at all. WDS `getAllCubesListLite` returns 8,274 tables in a
5 MB JSON array in about 4 seconds. Each entry has `productId`, `cubeTitleEn`, `cubeTitleFr`, and
`archived`. `archived` is the string `"2"` for current tables (5,083, including 17100005 and
18100004) and `"1"` for archived ones (3,191, including 10100001, which ends in 2011).

`list_datasets` asks for `references=datastructure` on the whole catalog. Serving it means 5,083
bundle downloads. StatGPT tolerates a catalog without DSDs: `_get_dataset_descriptor` catches the
failure and falls back to `SdmxDataSetConfigTemplate`. The registry config downgrades the
reference to `none` through `supportedReferencesForAll`, which design 041 introduced for this
pattern on Eurostat.

### B3: codelist and concept-scheme URN collisions

Every bundle names its codelists `StatCan:CL_{dimensionId}(1.0)` and its concept scheme
`StatCan:CONCEPTS(1.0)`, whatever the table. The content differs:

| Artefact | In `Data_Structure_17100005` | In `Data_Structure_18100004` |
|----------|------------------------------|------------------------------|
| `StatCan:CL_Geography(1.0)` | 15 codes | 30 codes |
| `StatCan:CL_DGUID(1.0)` | 14 codes | 29 codes |
| `StatCan:CL_UOM(1.0)` | 2 codes | 11 codes |
| `StatCan:CONCEPTS(1.0)` | 14 concepts | Different concepts |

So `codelist/StatCan/CL_Geography/1.0` has no single answer. StatGPT sends an
`X-Source-Artefact-Urn` header with the DSD URN on codelist and concept-scheme requests
(`statgpt_sdmx_proxy/v30/sdmx_client.py`, `proxy_structure_extra_headers`). The proxy reads it only
to route the request (`SdmxStructure30Controller` passes it to `AgencyRoutingService`), not to tell
artefacts apart. StatGPT's own cache keys on the URL only (`sdmx/v21/sdmx_client.py`, `_get`,
`key=req.url`), so it would serve the first table's `CL_Geography` for every later table even if the
proxy honored the header.

The proxy therefore makes the identifiers unique per table: `CL_Geography` in table 17100005
becomes `CL_17100005_Geography`, and `CONCEPTS` becomes `CONCEPTS_17100005`. The StatCan user guide
itself refers to the scheme as `CONCEPTS_17100005`. A renamed ID carries its table number, so the
proxy can map any request back to the right bundle, and StatGPT's URL cache stays correct.

### B4: flowRef

`GenericRegistryAdapterImpl.getFlowRef` always builds `agency,id,version`. StatCan answers `404` to
every form except the bare ID, and the unit test `GenericRegistryAdapter21Test` pins the
three-part form for OECD.

`getData21` also always appends the provider segment: it calls
`Sdmx21DataClient.getData(contentType, flowRef, key, providerRef, queryMap)` with `providerRef =
"all"`, which maps to `GET /{flowRef}/{key}/{providerRef}`. StatCan answers `404` to any provider
segment. The client's two-segment overload `GET /{flowRef}/{key}` has no `@QueryMap`, so it can't
carry `startPeriod` and the other parameters.

### B5: availability

StatCan has no `availableconstraint` resource and no `contentconstraint`. Design 040 emulation
covers this. A filtered request already goes through a data probe. The unfiltered request defaults
to `CONTENT_CONSTRAINT`, which StatCan can't serve, so the registry config sets
`unfilteredSource: PROBE`. The probe key is then `all`, which `R2` handles. StatCan ignores
`detail`, so every probe downloads full data (14 MB for the whole of 17100005, well below the
256 MB `maxProbeBytes`). The probe
format must be XML, because StatCan has no CSV and the harvesters read only CSV and XML.

### B6: HTTP 409 during updates

The WDS user guide says that from midnight to 8:30 a.m. Eastern time, tables being updated can
return `409`. `Resilience4jComponentFactory.upstreamFailurePredicate` retries only `IOException`
and status 500 and above, so a `409` fails the request immediately.

### B7: malformed empty results

When nothing matches, StatCan answers `200` with an empty `message:DataSet` that holds a stray
closing tag: `<message:DataSet ...></Series> </message:DataSet>`, or `</generic:Series>` in generic
data. An XML parser rejects it ("mismatched tag"). This happens for an empty time range, for an
unknown code, and for any narrowed key without data. The availability probe meets it constantly,
because narrowed probes often match nothing.

The router applies data fixtures only on the data conversion path (`AdapterRouterImpl`, around line
397). `RouterAvailabilityEmulationContext.fetchData` returns the raw stream to the harvester with no
fixture pass.

### B8: the 2.1 availability probe drops the time filter

StatGPT's availability call is a `POST` with a `TIME_PERIOD` filter. `AvailabilityQuery30Controller`
passes `null` for `startPeriod` and `endPeriod`, because SDMX 3.0 has none. For SDMX 2.1,
`QueryTranslatorImpl.translateAvailabilityQuery` folds the non-time filters into the key but,
unlike `translateDataQuery`, never derives `startPeriod` and `endPeriod` from the time filter.
`ProbeQueryBuilder.build` copies `key` and `filters` only, and `getData21` never sends `filters`.
So every StatCan probe downloads the whole matching table regardless of the time window, and the
emulated constraint reports codes that have no observations in the window. The 3.0 path (Eurostat)
is unaffected, because there the time filter travels in `filters`.

## Current architecture

The following diagram shows the structure and data paths this design touches:

```
Structure request                         Data request
SdmxStructure30Controller                 DataQuery30Controller
  -> QueryTranslatorImpl                    -> QueryTranslatorImpl (key -> toKey -> "all")
     .translateStructureQuery                -> AdapterRouterImpl.getData
       (ReferencesResolver clamps refs)          -> getSdmxBeans(dataflow ... descendants)
  -> AdapterRouterImpl.getStructures              -> getStructureBytes (raw cache)
     -> GenericRegistryAdapterImpl                   -> GenericRegistryAdapterImpl.getStructures
        .getStructures                         -> resolveRawDataStream
        -> getStructures21                         -> fetchData
           -> Sdmx21StructureClient                    -> GenericRegistryAdapterImpl.getData21
              GET /{type}/{agency}/{id}/{version}         flowRef = agency,id,version
     -> StructureFixtureService.applyFixtures              key     = toKey(key)
     -> StreamingStructureConversionService             -> Sdmx21DataClient
                                                   -> DataFixtureService, conversion
```

## Solution

The design has five parts, implemented in the order listed.

1. **A structure source for StatCan.** `GenericRegistryAdapterImpl.getStructures21` checks a new
   `structureSource` block on the structure endpoint. When its type is `STATCAN`, the adapter hands
   the query to `StatCanStructureSource` instead of `Sdmx21StructureClient`. The source returns an
   SDMX-ML 2.1 structure document with exactly the requested artefacts, so the router, the raw and
   ready-response caches, the fixtures, and the converters all work unchanged.
2. **Generic data-path flags.** `flowRefIdOnly` and `omitProviderRef` (`B4`), and
   `expandMatchAllKey` (`R2`), on
   `DataEndpointConfiguration`, and `retryOnStatuses` (`B6`) on `RegistryRetryConfig`.
3. **The `FILL_MISSING_REPRESENTATIONS` fixture** for XML 2.1 structures (`R1`). It is generic,
   because the reporters also name the Australian Bureau of Statistics for this defect. A second,
   data fixture `REPAIR_EMPTY_DATASET` repairs `B7`.
4. **Time window on the emulated 2.1 probe** (`B8`).
5. **Configuration and tests.** A StatCan registry config, an E2E suite, and generic E2E pins that
   StatCan turns on (step 14).

### Why an adapter-level source

The adapter is the only class that talks to Feign clients, and `AdapterRouterImpl` reaches every
structure through `genericRegistryAdapter.getStructures` (`getFixedStructureStream`,
`getStructuresConversion`, `getStructuresBypass`, and the emulation context's `fetchStructure`). A
source behind that single method covers all four call sites. The router-level alternative would
have meant a second dispatch in each of them.

The source can't leave `R1` to the router's fixture pass. Copying a representation onto a concept
needs the DSD, and the source cuts the DSD out when the client asks for a concept scheme alone. So
the source applies the configured structure fixtures to the whole renamed bundle before it selects
artefacts. The router then applies them again to the selected subset. The fixture only fills what
is missing, so the second pass changes nothing; the unit tests pin that it is idempotent.

### Request flow with the source

The following worked example shows how StatGPT loads table 17100005:

| Step | Client request | StatCan or WDS request | Answer |
|------|----------------|------------------------|--------|
| 1 | `dataflow/all/all/latest?references=datastructure` | WDS `getAllCubesListLite` (cached) | 5,083 `Dataflow` stubs, no DSDs |
| 2 | `dataflow/StatCan/DF_17100005/latest?references=datastructure` | `structure/Data_Structure_17100005` (cached) | Dataflow plus DSD, identifiers renamed |
| 3 | `conceptscheme/StatCan/CONCEPTS_17100005/1.0` | Bundle from the cache | `CONCEPTS_17100005` with core representations |
| 4 | `codelist/StatCan/CL_17100005_Geography/1.0` | Bundle from the cache | One codelist |
| 5 | `POST availability/dataflow/StatCan/DF_17100005/latest` with a time filter only | `data/DF_17100005/.2+3+1.?startPeriod=...&endPeriod=...` (step 11b) | Emulated constraint |
| 6 | `data/dataflow/StatCan/DF_17100005/latest/1.1.*` | `data/DF_17100005/1.1.` | Data, converted from XML |

`CL_Gender` is the smallest codelist in 17100005, with three codes in the order `2`, `3`, `1`.

## Implementation plan

### Step 1: Add the configuration model

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureSourceType.java` (New)

```java
package com.epam.sdmxproxy.configuration.data;

/**
 * Where a structure endpoint gets its structures from.
 */
public enum StructureSourceType {
    /** Standard SDMX structure resources at {@code structureEndpointConfig.url}. */
    SDMX,
    /**
     * Statistics Canada: per-table bundles from {@code GET {url}structure/Data_Structure_{table}},
     * and the dataflow catalog from the WDS {@code getAllCubesListLite} method -- see design 043.
     */
    STATCAN
}
```

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureSourceConfiguration.java` (New)

```java
package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

@Data
public class StructureSourceConfiguration {

    private StructureSourceType type = StructureSourceType.SDMX;

    /** Base URL of the non-SDMX catalog API. For STATCAN, the WDS base {@code .../t1/wds/rest/}. */
    private String catalogUrl;

    /** When false, the catalog lists current tables only (WDS {@code archived == "2"}). */
    private boolean includeArchived;
}
```

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureEndpointConfiguration.java` (Modified)

Add the following field after `referencesDowngrade`:

```java
    /**
     * Where this endpoint's structures come from. Null means standard SDMX resources at
     * {@link #getUrl()}. See {@link StructureSourceType}.
     */
    private StructureSourceConfiguration structureSource;
```

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/DataEndpointConfiguration.java` (Modified)

Add the following fields after `emptyOnTimePeriodRejection`:

```java
    /**
     * When true, the SDMX 2.1 data {@code flowRef} is the bare dataflow ID instead of
     * {@code agency,id,version}. Statistics Canada answers 404 to every other form, although its
     * user guide documents them. Has no effect on SDMX 3.0 registries.
     */
    private boolean flowRefIdOnly;

    /**
     * When true, the SDMX 2.1 data path ends after the key: {@code GET /{flowRef}/{key}} instead of
     * {@code GET /{flowRef}/{key}/all}. Statistics Canada answers 404 to any provider segment. Has
     * no effect on SDMX 3.0 registries.
     */
    private boolean omitProviderRef;

    /**
     * When true, a key that matches everything -- no key, {@code *}, {@code all}, or wildcards in
     * every position -- is sent as an OR over every code of the dimension with the smallest
     * codelist, for example {@code .2+3+1.}. Statistics Canada rejects {@code all} with 400 and an
     * all-empty key with 404. The OR selects the same series, so the response is unchanged. The
     * decision ignores time filters, which travel as {@code startPeriod} and {@code endPeriod}.
     * SDMX 2.1 registries only.
     */
    private boolean expandMatchAllKey;
```

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/RegistryRetryConfig.java` (Modified)

Add `private Set<Integer> retryOnStatuses;`. Null or empty keeps today's predicate (`IOException`
or status 500 and above). StatCan sets `[409]`.

**File:** `sdmx-proxy-config/README.md` (Modified)

Add the six fields (`structureSource`, `flowRefIdOnly`, `omitProviderRef`, `expandMatchAllKey`,
`retryOnStatuses`, and the `StructureSourceConfiguration` fields as their own table) to the schema
tables, a `FILL_MISSING_REPRESENTATIONS` row to the structure fixture table, and a
`REPAIR_EMPTY_DATASET` row to the data fixture table (formats XML 2.1 structure-specific and
generic, also applied to availability probes), as `CLAUDE.md` requires for every config field
change.

**Key points:**

- `ConfigValidator`
  (`sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigValidator.java`)
  gets three rules: `STATCAN` requires `catalogUrl`, `STATCAN` requires `sdmxVersion: SDMX_2_1`, and
  `STATCAN` requires structure `supportedFormats` to be exactly `[XML_STRUCTURE_2_1]`.
- `ConfigValidator` runs only in the config server (`ConfigService`). The proxy's own config
  loading and its `POST /config`, which E2E uses, don't call it. The proxy therefore also checks
  the last two rules at runtime, in `StatCanStructureSource` (step 7).
- `includeArchived` defaults to `false`, the user's decision.

### Step 2: Add the StatCan Feign clients

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/client/StatCanStructureClient.java` (New)

```java
package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.io.InputStream;

/**
 * Statistics Canada serves one structure resource: a bundle with the agency scheme, the dataflow,
 * the DSD, its codelists, and its concept scheme. See design 043.
 */
public interface StatCanStructureClient extends SdmxApiBase {

    @RequestLine("GET /structure/{dsdId}")
    @Headers("Accept: application/vnd.sdmx.structure+xml;version=2.1")
    InputStream getBundle(@Param("dsdId") String dsdId);
}
```

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/client/StatCanWdsClient.java` (New)

```java
package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.RequestLine;

import java.io.InputStream;

public interface StatCanWdsClient extends SdmxApiBase {

    @RequestLine("GET /getAllCubesListLite")
    @Headers("Accept: application/json")
    InputStream getAllCubesListLite();
}
```

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/SdmxApiClientProvider.java` and `SdmxApiClientProviderImpl.java` (Modified)

Add `getStatCanStructureClient(RegistrySelectionResult)`, built on `structureEndpointConfig.url`,
and `getStatCanWdsClient(RegistrySelectionResult)`, built on
`structureEndpointConfig.structureSource.catalogUrl`. Both go through `getOrBuildClient`, so they
inherit the registry's circuit breaker, retry, rate limit, and timeouts. `buildKey` already includes
the class name, so they don't share instances with the SDMX clients.

### Step 3: Retry the configured statuses

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/Resilience4jComponentFactory.java` (Modified)

`upstreamFailurePredicate()` (around line 215) becomes `upstreamFailurePredicate(Set<Integer>
extraStatuses)`. A `FeignException` counts as a failure when `status() >= 500` or its status is in
`extraStatuses`. The retry (line 96) passes the registry's `retryOnStatuses`. The circuit breaker
(line 61) passes an empty set, because a table locked for an update says nothing about the health of
the registry.

### Step 4: Map StatCan identifiers

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/structure/statcan/StatCanIdentifiers.java` (New)

An injectable `@Service` (the project prefers services over static utilities) that owns every
naming rule:

```java
public Optional<String> tableOf(String structureType, String id)
public String dsdIdOf(String table)                       // "Data_Structure_" + table
public String renamedCodelistId(String table, String id)  // "CL_Geography" -> "CL_17100005_Geography"
public String renamedConceptSchemeId(String table, String id) // "CONCEPTS" -> "CONCEPTS_17100005"
```

`tableOf` recognizes the following patterns, where a table is exactly eight digits:

| Structure type | Pattern | Example |
|----------------|---------|---------|
| `dataflow` | `DF_(\d{8})` | `DF_17100005` |
| `datastructure` | `Data_Structure_(\d{8})` | `Data_Structure_17100005` |
| `codelist` | `CL_(\d{8})_.+` | `CL_17100005_Geography` |
| `conceptscheme` | `CONCEPTS_(\d{8})` | `CONCEPTS_17100005` |

Any other ID returns empty, and the source throws a new `StructureNotFoundException` in
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/`. No proxy exception maps to `404` today:
`GlobalExceptionHandler` reaches `404` only through `FeignException` (upstream status passed
through) and `NoResourceFoundException`. Follow the family convention (`BadRequestException`,
`NotImplementedException`, `ServiceUnavailableException`, one handler per HTTP status family): add
an abstract `NotFoundException extends BaseException`, make `StructureNotFoundException` its
concrete subclass, and add a `NotFoundException` handler next to `handleNotImplemented` that
answers `HttpStatus.NOT_FOUND`.

For the `501` case, add `StructureListNotSupportedException` in the same package. It extends
`NotImplementedException`, which is abstract with protected constructors; its concrete siblings are
`AvailabilityNotSupportedException`, `UnsupportedAgencyWildcardException`, and
`UnsupportedConversionException`.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java` (Modified)

Both exceptions are thrown inside `getStructuresConversion`'s `StreamingResponseBody`, which is the
path every StatCan structure request takes (bypass is off). Its catch block rethrows only
`FeignException` and wraps everything else in `StructureConversionException`, a `500`. Add a
`catch (BaseException e) { throw e; }` before the generic catch, so the handler sees the original
exception. Without it, every `404` and `501` the source raises reaches the client as `500`. The
same dispatch already carries `FeignException` out of the `StreamingResponseBody`, and the body is
buffered until conversion ends, so the response isn't committed when the exception fires.

This is an intended status change for every registry. Exceptions that were wrapped into `500` on
this path now keep their own status: `RateLimitExceededException` becomes `429`,
`UnsupportedConversionException` (from `StreamingStructureConversionService`) `501`, and
`CacheUnavailableException` and `RegistryUnavailableException` `503`. No existing test pins the old
`500`.

### Step 5: Rename the bundle

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/structure/statcan/StatCanBundleRenamer.java` (New)

A StAX copy of the bundle (`XMLEventReader` to `XMLEventWriter`, the pattern the XML data fixtures
use) that rewrites these items:

| Element | Attribute | Rewrite |
|---------|-----------|---------|
| `str:Codelist` | `id`, `urn` | Codelist ID renamed |
| `str:ConceptScheme` | `id`, `urn` | Scheme ID renamed |
| `Ref` with `class="Codelist"` | `id` | Codelist ID renamed |
| `Ref` with `class="Concept"` | `maintainableParentID` | Scheme ID renamed |
| `Ref` with `class="ConceptScheme"` | `id` | Scheme ID renamed |
| Any element | `urn` containing `Codelist=StatCan:CL_` or `ConceptScheme=StatCan:CONCEPTS(` | Same rename inside the URN |

The table comes from the request, not from the document. The renamer checks that the bundle's
`str:DataStructure` ID equals `dsdIdOf(table)` and fails otherwise, so a StatCan change in naming
surfaces as an error instead of silent mixing.

The renamer leaves dataflow and DSD IDs and agency IDs alone, because they are already unique per
table. Data messages reference only the dataflow, so data needs no rename.

### Step 6: Build the catalog

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/structure/statcan/StatCanCatalogWriter.java` (New)

The writer reads the WDS array with Jackson streaming (`JsonParser`), because the array is 5 MB. It
writes an SDMX-ML 2.1 `mes:Structure` message with one `str:Dataflow` per kept table:

```xml
<str:Dataflow id="DF_17100005" agencyID="StatCan" version="1.0"
              urn="urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=StatCan:DF_17100005(1.0)">
  <com:Name xml:lang="en">Population estimates on July 1, by age and gender</com:Name>
  <com:Name xml:lang="fr">Estimations de la population au 1er juillet, par âge et genre</com:Name>
  <str:Structure>
    <Ref id="Data_Structure_17100005" version="1.0" agencyID="StatCan"
         package="datastructure" class="DataStructure"/>
  </str:Structure>
</str:Dataflow>
```

**Key points:**

- Version `1.0` is what every probed bundle carries. The source treats the catalog as a list of
  stubs, and the real artefact comes from the bundle.
- A table is kept when `includeArchived` is true or `archived` equals `"2"`. Entries without a
  `productId` or `cubeTitleEn` are skipped with a `DEBUG` log.
- The writer emits no header fields beyond the ones the sdmx-core 2.1 reader requires. Copy the
  header shape from the probed bundle (`mes:ID`, `mes:Test`, `mes:Prepared`, `mes:Sender`).

### Step 7: Select the requested artefacts

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/structure/statcan/StatCanStructureSource.java` (New)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StatCanStructureSource {

    private final SdmxApiClientProvider sdmxApiClientProvider;
    private final StatCanIdentifiers identifiers;
    private final StatCanBundleRenamer renamer;
    private final StatCanCatalogWriter catalogWriter;
    private final StructureFixtureService structureFixtureService;
    private final StreamingStructureConversionService conversionService;
    private final CacheService cacheService;

    public InputStream getStructures(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry) {
        ...
    }
}
```

`getStructures` follows this algorithm:

0. Fail fast with `UnexpectedStateException` if the version isn't `SDMX_2_1` or
   `query.getRegistryReturnFormat()` isn't `XML_STRUCTURE_2_1`. These are the runtime copies of the
   `ConfigValidator` rules (step 1).
1. If the type is `dataflow` and the ID is `*` or `all`, return the catalog document. The agency
   must be `*`, `all`, or `StatCan`; any other agency returns an empty structure message.
2. If the ID is `*` or `all` for any other type, throw `StructureListNotSupportedException`
   (step 4), which `GlobalExceptionHandler` answers with `501`.
3. Resolve the table with `identifiers.tableOf`. No table means `StructureNotFoundException`.
4. The version must be `1.0`, `latest`, `+`, `*`, or `all`. Any other version means
   `StructureNotFoundException`. A StatCan `404` for the bundle stays a `FeignException` and passes
   through as `404`.
5. Load the prepared bundle for the table (see the next list).
6. Parse it with `conversionService.parseStructures(new ByteArrayInputStream(bytes),
   SdmxFormat.XML_STRUCTURE_2_1)` (the signature takes an `InputStream`), select
   the artefacts in the following table, put them in a `SdmxBeansImpl` with `addIdentifiables`,
   and write the result with `conversionService.convert(beans, out,
   MediaType.parseMediaType(SdmxMediaTypes.STRUCTURE_XML_2_1))`.

The prepared bundle is the raw StatCan bundle after the renamer and after
`structureFixtureService.applyFixtures(..., SdmxFormat.XML_STRUCTURE_2_1,
structureEndpointConfig.getFixtures())`. It is cached as raw structures under
`statcan:bundle:{registryName}:{table}`, so the 12 or so requests StatGPT sends per table cost one
StatCan download. The catalog is cached under `statcan:catalog:{registryName}:{includeArchived}`.
Both entries use the raw-structure TTL, and both are flushed with the rest of the cache on a config
change (design 041, `P8`).

Concurrent misses for one table aren't single-flighted: two parallel requests can both download
the bundle. The cost is one extra download per race, which is acceptable for a first version.

The source selects artefacts by `references` as follows. A null `references`, which StatGPT's
codelist and concept-scheme calls send, counts as `none`. `ancestors` counts as `parents`, because a
bundle has no ancestor beyond the dataflow. A structure type name not in the table, such as
`dataflow` on a `codelist` request, selects nothing extra, so it behaves as `none`.

| Requested type | `none` or null | `parents`, `parentsandsiblings`, `ancestors` | `children`, `datastructure`, `codelist`, `conceptscheme` | `descendants`, `all` |
|----------------|----------------|----------------------------------------------|--------------------------------------------------------|----------------------|
| `dataflow` | Dataflow | Dataflow | Dataflow plus the named type (`children` = DSD) | Whole bundle minus the agency scheme |
| `datastructure` | DSD | DSD plus dataflow | DSD plus the named type (`children` = codelists and scheme) | DSD plus codelists and scheme |
| `codelist` | Codelist | Codelist plus DSD | Codelist | Codelist |
| `conceptscheme` | Scheme | Scheme plus DSD | Scheme plus the codelists of its core representations | Same as `children` |

The bundle holds exactly one dataflow and one DSD, so no graph walk is needed. `ReferencesResolver`
has already clamped `references` to the registry's supported set before the source runs.

### Step 8: Dispatch in the adapter

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java` (Modified)

Inject `StatCanStructureSource` and branch at the top of `getStructures21`:

```java
    private InputStream getStructures21(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry, Structure structure) {
        StructureSourceConfiguration source = selectedRegistry.getVersionConfiguration().getStructureEndpointConfig().getStructureSource();
        if (source != null && source.getType() == StructureSourceType.STATCAN) {
            return statCanStructureSource.getStructures(query, selectedRegistry);
        }
        Sdmx21StructureClient structure21Client = sdmxApiClientProvider.getStructure21Client(selectedRegistry);
        ...
```

The source returns XML 2.1, so the StatCan structure endpoint must list only `XML_STRUCTURE_2_1` in
`supportedFormats`. `ConfigValidator` enforces that for `STATCAN`.

### Step 9: Send a bare flowRef

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java` (Modified)

`getFlowRef` is shared by `getData21` and `getAvailability21`, so it stays as it is. `getData21`
branches instead: when the data endpoint has `flowRefIdOnly`, it uses
`sdmx21QueryNormalizer.toSdmx21PathSlot(query.getResourceID())` as the flowRef, and calls
`getFlowRef` otherwise. `getAvailability21` keeps the three-part form, because the flag is named
and documented for data. StatCan never reaches `getAvailability21`, because its availability is
emulated.

When the data endpoint has `omitProviderRef`, `getData21` calls a new `Sdmx21DataClient` overload
instead of the four-segment one:

```java
    @RequestLine("GET /{flowRef}/{key}")
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef,
            @Param("key") String key,
            @QueryMap Map<String, Object> queryMap
    );
```

The existing two-segment overload has no `@QueryMap` and can't carry `startPeriod`, so it isn't
reused. **File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/client/Sdmx21DataClient.java` (Modified).

### Step 10: Expand the match-all key

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/MatchAllKeyExpander.java` (New)

```java
@Service
public class MatchAllKeyExpander {

    /**
     * Returns the SDMX 3.0 key {@code "*.1+2+3.*"} for a DSD whose smallest enumerated, non-time
     * dimension is the second one with codes 1, 2, and 3. Ties go to the earlier dimension.
     */
    public String expand(SdmxBeans beans) {
        ...
    }
}
```

The expander returns an SDMX 3.0 key, so the normalizer at the adapter boundary still turns `*`
into empty positions. It reads codes from the codelist each dimension's representation references,
found in `beans`. If no non-time dimension is enumerated, it throws `UnexpectedStateException`
naming the DSD.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java` (Modified)

`fetchData` is the one method that every data request passes through: plain data, limit emulation,
and the availability probe (`RouterAvailabilityEmulationContext.fetchData`). Add the expansion
there:

```java
    private InputStream fetchData(TranslatedDataQuery query) {
        try {
            return orEmpty(genericRegistryAdapter.getData(expandMatchAllKey(query)));
        ...
    }

    private TranslatedDataQuery expandMatchAllKey(TranslatedDataQuery query) {
        DataEndpointConfiguration dataConfig = query.getVersionConfiguration().getDataEndpointConfig();
        if (dataConfig == null || !dataConfig.isExpandMatchAllKey() || !availabilityQueryCanonicalizer.isWildcardKey(query.getKey())) {
            return query;
        }
        SdmxBeans beans = getSdmxBeans(getStructureQuery(query));
        return query.toBuilder().key(matchAllKeyExpander.expand(beans)).build();
    }
```

`expand(SdmxBeans beans)` finds the DSD itself: the beans come from a `dataflow ... descendants`
query, so they hold exactly one DSD. No `firstDataStructure` helper exists in the router, so the
lookup lives in the expander, which throws `UnexpectedStateException` when the beans hold no DSD.

**Key points:**

- The decision rests on the key alone, never on `query.getFilters()`. For SDMX 2.1,
  `QueryTranslatorImpl.translateDataQuery` folds the non-time `c[]` filters into the key and turns
  `c[TIME_PERIOD]` into `startPeriod` and `endPeriod`, yet still sets `.filters(filters)` with the
  original map (around line 582). StatGPT always sends a time filter
  (`statgpt-backend/statgpt/common/data/sdmx/common/query.py`, `get_params_v30`) and key `*` when
  nothing is narrowed. A filters guard would skip the expansion for exactly that request, and
  StatCan would answer `400`. `translateAvailabilityQuery` and `ProbeQueryBuilder.build` keep the
  filters the same way. `getData21` never sends `filters`, so ignoring them is safe. The flag is
  documented as SDMX 2.1 only.
- The match-all rule already exists as `AvailabilityQueryCanonicalizer.isWildcardKey` (a
  `@Component`): null, blank, `*`, `all`, or only `*` or empty positions. Inject and reuse it, so
  the rule lives in one place. `Sdmx21QueryNormalizer` stays unchanged.
- `getSdmxBeans` reads the raw-structure cache, which the caller has already warmed, so the second
  lookup costs a parse and no network call.
- The bypass branch in `getData` calls `genericRegistryAdapter.getData` directly. Wrap its query in
  `expandMatchAllKey(query)` there too, so the flag can't be silently skipped. Don't route the
  branch through all of `fetchData`: `fetchData` turns a matching `FeignException` into
  `RegistryNoDataException`, which the bypass lambda doesn't catch, and that would change error
  behavior for other registries. StatCan disables bypass, so this is a guard only.
- A filtered request with `convertKeyToFilters` is out of scope: that flag targets 3.0 registries.

### Step 11: Fill missing representations

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/StructureFixtureType.java` (Modified)

Add `FILL_MISSING_REPRESENTATIONS`.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/FillMissingRepresentationsXmlFixture.java` (New)

The fixture supports `XML_STRUCTURE_2_1` only. In an SDMX-ML 2.1 message the concept schemes come
before the DSDs, so a single forward pass can't copy a component's representation onto its concept.
The fixture reads the document into bytes and makes two StAX passes:

1. **Collect.** Record which concepts already have a `str:CoreRepresentation`. For every DSD
   component (`str:Dimension`, `str:TimeDimension`, `str:Attribute`, `str:PrimaryMeasure`), record
   the concept reference (`ConceptIdentity/Ref`: agency, `maintainableParentID`,
   `maintainableParentVersion`, `id`) and the component's *effective* representation: the
   `LocalRepresentation` after the component rules in the following table are applied. So
   `TIME_PERIOD`'s empty `TextFormat` is recorded as `ObservationalTimePeriod`, and `VECTOR_ID`'s as
   `String`.
2. **Rewrite.** Apply these rules:

| Location | Condition | Result |
|----------|-----------|--------|
| Non-time component | No `str:LocalRepresentation` and its concept has a `CoreRepresentation` | Unchanged. The component inherits from the concept, as the SDMX 2.1 schema allows |
| Non-time component | No `str:LocalRepresentation` and its concept isn't in the document | Unchanged. The fixture can't tell whether the concept has a representation |
| Non-time component | No `str:LocalRepresentation` and its concept is in the document without one | `<str:LocalRepresentation><str:TextFormat textType="String"/></str:LocalRepresentation>` |
| Non-time component | Empty `<str:TextFormat/>` | Add `textType="String"` |
| `str:TimeDimension` | Missing or empty `TextFormat` | `textType="ObservationalTimePeriod"` |
| `str:Concept` | No `str:CoreRepresentation` and a component referencing it has an effective representation | Copy that representation as `CoreRepresentation` |
| `str:Concept` | No `str:CoreRepresentation`, no component references it, and the document holds at least one DSD | `<str:CoreRepresentation><str:TextFormat textType="String"/></str:CoreRepresentation>` |
| `str:Concept` | No `str:CoreRepresentation` and the document holds no DSD | Unchanged |

A document without both sides is left as it is, so the answer for one artefact doesn't depend on
how it was requested. For StatCan this never applies: the source runs the fixture on the whole
bundle (step 7), where the DSD and the concept scheme are both present.

**Key points:**

- The first row keeps the fixture generic. The 2.1 XSD says a component "takes its semantic, and in
  some cases its representation, from its concept identity"
  (`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/resources/xsd/2_1/SDMXStructureDataStructure.xsd`,
  `PrimaryMeasureType`, around line 489). Writing `String` locally there would override a concept's
  `Double` or enumeration, because a local representation wins.
- The time rule departs from "String where neither exists", on purpose. The SDMX 2.1 schema limits
  a time dimension to time text types, and `TimeTextFormat` defaults to `ObservationalTimePeriod`.
  sdmx-core already substitutes it on read
  (`sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/datastructure/DimensionBeanImpl.java`,
  `validateDimension`), so for the component the rule is redundant but harmless. It matters for the
  copy: the `TIME_PERIOD` concept gets `ObservationalTimePeriod` instead of `String`.
- An inserted `str:LocalRepresentation` goes directly after `str:ConceptIdentity`, before
  `str:ConceptRole` or `str:AttributeRelationship` (XSD `DimensionType` and `AttributeType`).
- The fixture runs on renamer output in the source and on the source's output in the router. Its
  unit tests therefore use a renamed bundle as input.
- The fixture ignores its `config` map, which is null for the StatCan entry.
- An enumeration copied onto a concept references the renamed codelist, because the renamer ran
  first. `StatGPT`'s `_get_code_lists` then requests the same codelist from both the concept and the
  component and deduplicates it.
- If two components reference one concept with different representations, the first one in
  document order wins, and the fixture logs a `WARN`.
- The fixture is idempotent: every rule tests for absence first.
- Element order inside `str:Concept` must follow the schema: annotations, names, descriptions,
  `Parent`, `CoreRepresentation`, `ISOConceptReference`. Insert before `str:ISOConceptReference`
  or before the end tag.

### Step 11a: Repair malformed empty results

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/DataFixtureType.java` (Modified)

Add `REPAIR_EMPTY_DATASET`.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/data/RepairEmptyDataSetXmlDataFixture.java` (New)

A `DataFixture` for `XML_STRUCTURE_SPECIFIC_DATA_2_1` and `XML_GENERIC_DATA_2_1`. The input isn't
well-formed, so StAX can't read it; the fixture works on bytes. It returns a `FilterInputStream`
that buffers from the start until it has read the dataset start tag and the next tag after it,
skipping whitespace. The dataset start tag is an element whose local name is exactly `DataSet`,
with any prefix, followed by whitespace, `>`, or `/>`. The header elements `message:DataSetID` and
`message:DataSetAction` come earlier and must not match. If that next tag is an end tag whose local name is `Series`
(`</Series>` or `</generic:Series>`), the fixture drops it. Everything else passes through
unchanged. The buffer is capped at 64 KB, which the header never reaches; past the cap the stream
passes through untouched. That satisfies the `DataFixture` contract: no full buffering.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java` (Modified)

The data conversion path already applies the data endpoint's fixtures. The probe doesn't:
`RouterAvailabilityEmulationContext.fetchData` hands the raw stream to the harvester. Apply the
configured `REPAIR_EMPTY_DATASET` fixture there as well, and only that type, through a new
`DataFixtureService.applyFixtures(InputStream, SdmxFormat, SdmxBeans, List<FixtureConfiguration<DataFixtureType>>, Set<DataFixtureType> onlyTypes)`
overload, next to the existing `applyFixtures(InputStream, SdmxFormat, SdmxBeans, List)`. The
probe passes `null` beans, because the repair needs none.

`DataFixtureService` applies fixtures in config order, and a StAX-based fixture such as
`TimePeriodMonthlyNormalizationXml*DataFixture` would fail on the malformed body. So the service
always runs `REPAIR_EMPTY_DATASET` first, whatever its position in the list. Other data fixtures, such as `TIME_PERIOD_MONTHLY_NORMALIZATION`, keep their scope.

### Step 11b: Time window on the emulated 2.1 probe

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java` (Modified)

Move the time-filter derivation in `translateDataQuery` (the `timeDimensionId` lookup,
`String.join(AND_SEPARATOR, timeValues)`, and the private static
`deriveStartEndFromTimeFilterValue`) into a private method that both translators call. In
`translateAvailabilityQuery`, for SDMX 2.1, call it when availability is emulated and the client
set no `startPeriod` or `endPeriod`, and set the result on the `TranslatedAvailabilityQuery`.
"Emulated" uses the router's own dispatch condition (`AdapterRouterImpl.getAvailability`), so the
two can't disagree: `availabilityConfig != null && !availabilityConfig.isAvailabilityEnabled() &&
emulation != null && emulation.getType() == AvailabilityEmulationType.DATA_QUERY`.

The emulation condition keeps the change away from real 2.1 availability endpoints (IMF 2.1, OECD).
`getAvailability21` forwards `startPeriod` and `endPeriod`, so deriving them for every 2.1 registry
would change what those registries receive. That wider fix is out of scope here (see open
questions).

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/availability/ProbeQueryBuilder.java` (Modified)

`build` copies `query.getStartPeriod()` and `query.getEndPeriod()` onto the probe.

### Step 12: Add the registry config

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/statcan/2_1/statcan_2_1_registry_config.json` (New)

```json
{
  "configs": [
    {
      "name": "StatCan",
      "description": "Statistics Canada (NSI Web Service 7.8, SDMX 2.1, structures emulated from per-table bundles and WDS)",
      "versions": {
        "SDMX_2_1": {
          "sdmxVersion": "SDMX_2_1",
          "structureEndpointConfig": {
            "url": "https://www150.statcan.gc.ca/t1/wds/sdmx/statcan/rest/",
            "supportedFormats": ["XML_STRUCTURE_2_1"],
            "defaultFormat": "XML_STRUCTURE_2_1",
            "bypassEnabled": false,
            "supportedStructures": ["dataflow", "datastructure", "codelist", "conceptscheme"],
            "supportedReferencesForAll": ["none"],
            "referencesDowngrade": {"datastructure": "none", "children": "none", "descendants": "none", "all": "none"},
            "structureSource": {
              "type": "STATCAN",
              "catalogUrl": "https://www150.statcan.gc.ca/t1/wds/rest/",
              "includeArchived": false
            },
            "fixtures": [{"type": "FILL_MISSING_REPRESENTATIONS"}]
          },
          "dataEndpointConfig": {
            "url": "https://www150.statcan.gc.ca/t1/wds/sdmx/statcan/rest/data/",
            "supportedFormats": ["XML_STRUCTURE_SPECIFIC_DATA_2_1", "XML_GENERIC_DATA_2_1"],
            "defaultFormat": "XML_STRUCTURE_SPECIFIC_DATA_2_1",
            "bypassEnabled": false,
            "supportsLimit": true,
            "flowRefIdOnly": true,
            "omitProviderRef": true,
            "expandMatchAllKey": true,
            "fixtures": [{"type": "REPAIR_EMPTY_DATASET"}]
          },
          "availabilityEndpointConfig": {
            "url": "https://www150.statcan.gc.ca/t1/wds/sdmx/statcan/rest/",
            "supportedFormats": ["XML_STRUCTURE_2_1"],
            "defaultFormat": "XML_STRUCTURE_2_1",
            "bypassEnabled": false,
            "availabilityEnabled": false,
            "emulation": {
              "type": "DATA_QUERY",
              "unfilteredSource": "PROBE",
              "probeFormat": "XML_STRUCTURE_SPECIFIC_DATA_2_1",
              "probeDetail": null
            }
          },
          "resilienceConfig": {
            "connectionTimeout": 30000,
            "readTimeout": 120000,
            "retry": {
              "maxAttempts": 3,
              "initialIntervalMillis": 2000,
              "multiplier": 2.0,
              "maxIntervalMillis": 8000,
              "retryOnStatuses": [409]
            }
          }
        }
      }
    }
  ],
  "agencies": [
    {"name": "StatCan", "primaryRegistry": "StatCan", "allowSubAgencies": false}
  ]
}
```

**Key points:**

- The emulation block follows the ESTAT config (`estat_3_0_registry_config.json`, lines 56-76).
  `DATA_QUERY` is the only non-`NONE` value of `AvailabilityEmulationType`.
- `supportsLimit: true` is required: `ConfigValidator` rejects `supportsLimit: false` together with
  emulated availability (design 040). The consequence is that a client's `limit` is silently
  dropped: `getData21` never sends `limit`, and with `supportsLimit: true` the router doesn't
  emulate it. This is a known limitation (edge case 11). StatGPT doesn't send `limit`.
- `probeDetail: null` drops `detail`, which StatCan ignores anyway.
- `referencesDowngrade` maps the wildcard catalog's `datastructure` to `none`. On one dataflow,
  `datastructure`, `children`, and `descendants` are served from the bundle, so
  `supportedReferences` stays null (no check).
- `allowSubAgencies: false`, because StatCan has no sub-agencies and discovery would download the
  catalog for nothing.
- The rate limits StatCan publishes for WDS (25 requests per second per IP) are far above what
  the proxy sends, so no `rateLimitRetry` block is needed.

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` (Modified)

The shipped registry list lives here; `sdmx-proxy/build.gradle` copies it into the proxy, and it
already lists ESTAT and OECD. Add the same StatCan registry and agency entries.

### Step 13: Add the E2E suite

**File:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/STATCAN_2_1_RegistryTestSuit.java` (New)

A copy of `OECD_2_1_RegistryTestSuit.java` that loads the two `statcan/2_1/` files.

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/statcan/2_1/statcan_2_1_test_config.json` (New)

The blocks follow the framework's shapes in `sdmx-proxy-e2e/.../framework/config/`:

| Block | Content |
|-------|---------|
| `structuresTestSuitConfiguration` | `artefacts` as `{type, urn}` objects (`StructureTypeAndUrn`): `dataflow` `StatCan:DF_17100005(1.0)`, `datastructure` `StatCan:Data_Structure_17100005(1.0)`, `codelist` `StatCan:CL_17100005_Geography(1.0)`, `conceptscheme` `StatCan:CONCEPTS_17100005(1.0)`, `dataflow` `StatCan:DF_18100004(1.0)`. `registryReturnFormats: [XML_STRUCTURE_2_1]`. `mediaTypes`: JSON 2.0, XML 2.1, XML 3.0 |
| `dataTestSuitConfiguration` | `dataflowConfigs`: `DF_17100005` keys `1.1.1`, `*.1.*`, `999.1.1` (no data, pins `B7`); `DF_18100004` key `2.2`. Key `*` is left to the `unfilteredData` pin, because each `*` run downloads 14 MB. `mediaTypes`: JSON 1.0, JSON 2.0, XML 2.1 structure-specific. `registryReturnFormats`: structure-specific and generic 2.1 |
| `availabilityTestSuitConfiguration` | `dataflowConfigs`: `DF_17100005` keys `*` and `1.*.*`. One media type and `registryReturnFormats: [XML_STRUCTURE_2_1]`, because every `*` run downloads the whole table (14 MB) unless the ready-response cache hits |
| `queryShapeTestSuitConfiguration` | `dataflowUrn: StatCan:DF_17100005(1.0)`, `orKey: "1.1+2+3.1"`, `emptyTimeRange: "ge:1800+le:1801"` (the probed empty range; a plain or `eq:` value derives no `startPeriod`, because only `ge`, `gt`, `le`, and `lt` count) so `testDataWithEmptyTimeRangeReturnsOk` pins key `*` plus a time filter (`R2`) and the malformed empty body (`B7`: StatCan answers `200` with the stray tag), plus the new fields in step 14 |
| `dsdFidelityTestSuitConfiguration` | `dsdUrn: StatCan:Data_Structure_17100005(1.0)`, plus the new fields in step 14 |
| `filterKeyOrderTestSuitConfiguration` | `dataflowUrn: StatCan:DF_17100005(1.0)`, filters `Geography=1` and `Gender=1` |

### Step 14: Add the generic E2E pins

Each pin is a field on an existing optional block in `BaseRegistryTestSuite`, gated by
`Assumptions.assumeTrue`, so other registries stay unaffected. That follows the rule that
cross-registry checks are config blocks, not `@Test` methods on one suite.

| Field | Block | Test method | Assertion |
|-------|-------|-------------|-----------|
| `unfilteredData` (boolean) | `queryShapeTestSuitConfiguration` | `testUnfilteredDataReturnsData` | `data/dataflow/{df}/*` returns `200` with at least one series (`R2`) |
| `longOrKey` (string), `longOrKeyDataflowUrn` (string) | `queryShapeTestSuitConfiguration` | `testLongOrKeyReturnsData` | The key returns `200` with a body, and the test asserts the outbound path is at least 5,000 characters (`R2`). 139 `Age_group` codes of 17100005 give only about 600 characters. The implementer picks a current table, using WDS, whose largest codelist gives 5,000 to 7,000 characters, and records it in the test config |
| `expectEmptyAvailabilityForEmptyTimeRange` (boolean) | `queryShapeTestSuitConfiguration` | `testAvailabilityWithEmptyTimeRangeReturnsOk` (extended) | With the flag set, the response for `emptyTimeRange` has one key per non-time dimension, and every key value set is empty (`B8`). The non-empty key map guards against a response with no constraint at all. Without the time window the probe returns every code, so the old code fails this. The emulated constraint carries no `TIME_PERIOD` (`includeTimePeriod` defaults to `false`, and `XmlSeriesKeyHarvester` reads only non-time dimensions), so the pin works through the dimension codes. The test also covers `B7` on the probe path, because the empty probe body is malformed. A field, not a change to the existing assertion, so registries that set `emptyTimeRange` today keep their behavior |
| `jsonSeriesKeyFormat` (boolean) | `queryShapeTestSuitConfiguration` | `testJsonSeriesKeysUseColon` | Requests `dataflowUrn` with `orKey`; every key under `dataSets[*].series` (JSON 1.0) and `data.dataSets[*].series` (JSON 2.0) matches `^\d+(:\d+)*$` (`R3`) |
| `expectAllRepresentations` (boolean) | `dsdFidelityTestSuitConfiguration` | `testAllComponentsHaveRepresentation` | Requests `dsdUrn` with `references=children` in JSON 2.0. Every DSD component has `localRepresentation` or its concept has a non-empty `coreRepresentation`, and every concept in the returned schemes has a non-empty `coreRepresentation` (`R1`) |
| `expectedConceptEnumerations` (map, concept ID to codelist URN) | `dsdFidelityTestSuitConfiguration` | `testAllComponentsHaveRepresentation` | Same response: StatCan maps `Geography` to `StatCan:CL_17100005_Geography(1.0)`, which pins the copy rule. JSON 2.0 carries a full `urn:sdmx:...Codelist=` URN, so the assertion is `endsWith` |
| `uniqueCodelistDsdUrns` (list of DSD URNs) | `dsdFidelityTestSuitConfiguration` | `testCodelistIdsUniquePerTable` | Fetches each DSD with `references=children`; no codelist URN appears in two of them with different codes (`B3`). StatCan lists 17100005 and 18100004 |

The `references=none` request that `testDsdConversionFidelity` sends returns no concept schemes,
which is why the representation pin asks for `children`. The new pins pass `references` and
`detail` through the RestAssured params map, as `fetchDeclaredDimensionOrder` does, because a
query string embedded in the path can be dropped silently (see the comment in
`BaseRegistryTestSuite`, around line 1030).

### Step 15: Check StatGPT without FREQ

This step is manual and produces no code. After steps 1-14 pass E2E, connect StatGPT dev to a
proxy with the StatCan registry and ask for both test tables: "Population of Canada in 2020" (table
17100005) and "Canada CPI for the last 12 months" (table 18100004). Record the following:

1. Whether indexing finishes for both tables.
2. Whether the time filter on the monthly table returns monthly periods.
3. Any error that names frequency.
4. Any "Bad request for URL" line in the StatGPT logs. StatGPT swallows a failed availability call
   into empty constraints, so this log line is the only sign of it.

Record the result in this design's Review Log or in a follow-up issue. If StatGPT fails, open a
follow-up design that synthesizes `FREQ` from WDS `frequencyCode`. It must decide how to represent
code `18` (occasional) and the other StatCan codes without an SDMX `CL_FREQ` equivalent.

## No changes required

These files need no modification:

- **`QueryTranslatorImpl.java`**, beyond step 11b: registry selection, `references` clamping, and
  the 2.1 key translation stay as they are. The key expansion happens later, in `fetchData`.
- **`StreamingStructureConversionService.java`**, **`StreamingDataConversionService.java`**: the
  source returns XML 2.1, and data stays XML 2.1. Both are already supported.
- **`CustomSdmxJsonDataReaderFactory.java`** and the JSON 1.0 reader: the proxy never reads StatCan
  JSON (`R3`).
- **`AgencySchemeService.java`**: it builds the agency scheme from the configuration.
- **`Sdmx21QueryNormalizer.java`**, **`Sdmx21QueryNormalizerImpl.java`**: the expansion reuses
  `AvailabilityQueryCanonicalizer.isWildcardKey`, and `toKey` already turns `*.2+3+1.*` into
  `.2+3+1.`.
- **`DataQueryAvailabilityEmulator.java`**: the probe already goes through
  `RouterAvailabilityEmulationContext.fetchData`, which reaches the expansion.
- **`TimePeriodMonthlyNormalization*DataFixture.java`**: StatCan XML already sends `2026-08`.

## Files affected

| File | Change type | Description |
|------|-------------|-------------|
| `sdmx-proxy-config/.../configuration/data/StructureSourceType.java` | New | `SDMX`, `STATCAN` |
| `sdmx-proxy-config/.../configuration/data/StructureSourceConfiguration.java` | New | `type`, `catalogUrl`, `includeArchived` |
| `sdmx-proxy-config/.../configuration/data/StructureEndpointConfiguration.java` | Modified | `structureSource` |
| `sdmx-proxy-config/.../configuration/data/DataEndpointConfiguration.java` | Modified | `flowRefIdOnly`, `omitProviderRef`, `expandMatchAllKey` |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` | Modified | StatCan registry and agency |
| `sdmx-proxy-config/.../configuration/data/RegistryRetryConfig.java` | Modified | `retryOnStatuses` |
| `sdmx-proxy-config/.../configuration/data/fixture/StructureFixtureType.java` | Modified | `FILL_MISSING_REPRESENTATIONS` |
| `sdmx-proxy-config/README.md` | Modified | Schema tables |
| `sdmx-proxy-config-server/.../service/ConfigValidator.java` | Modified | Three `STATCAN` rules |
| `sdmx-proxy/.../registry/api/client/Sdmx21DataClient.java` | Modified | `GET /{flowRef}/{key}` overload with `@QueryMap` |
| `sdmx-proxy/.../registry/api/client/StatCanStructureClient.java` | New | `GET /structure/{dsdId}` |
| `sdmx-proxy/.../registry/api/client/StatCanWdsClient.java` | New | `GET /getAllCubesListLite` |
| `sdmx-proxy/.../registry/api/SdmxApiClientProvider.java` | Modified | Two getters |
| `sdmx-proxy/.../registry/api/SdmxApiClientProviderImpl.java` | Modified | Two getters |
| `sdmx-proxy/.../registry/api/Resilience4jComponentFactory.java` | Modified | Extra retry statuses |
| `sdmx-proxy/.../exception/NotFoundException.java` | New | Abstract `404` family |
| `sdmx-proxy/.../exception/StructureNotFoundException.java` | New | Answered `404` |
| `sdmx-proxy-config/.../configuration/data/fixture/DataFixtureType.java` | Modified | `REPAIR_EMPTY_DATASET` |
| `sdmx-proxy/.../services/fixture/data/RepairEmptyDataSetXmlDataFixture.java` | New | `B7` |
| `sdmx-proxy/.../services/fixture/data/DataFixtureService.java` | Modified | `onlyTypes` overload |
| `sdmx-proxy/.../services/translator/QueryTranslatorImpl.java` | Modified | Time window for emulated 2.1 availability (`B8`) |
| `sdmx-proxy/.../services/availability/ProbeQueryBuilder.java` | Modified | Copy `startPeriod` and `endPeriod` |
| `sdmx-proxy/.../exception/StructureListNotSupportedException.java` | New | Concrete `NotImplementedException`, answered `501` |
| `sdmx-proxy/.../web/exception/GlobalExceptionHandler.java` | Modified | Handler for the abstract `NotFoundException` family |
| `sdmx-proxy/.../services/structure/statcan/StatCanIdentifiers.java` | New | Naming rules |
| `sdmx-proxy/.../services/structure/statcan/StatCanBundleRenamer.java` | New | Per-table rename |
| `sdmx-proxy/.../services/structure/statcan/StatCanCatalogWriter.java` | New | WDS to SDMX-ML dataflows |
| `sdmx-proxy/.../services/structure/statcan/StatCanStructureSource.java` | New | Selection, caching |
| `sdmx-proxy/.../services/adapter/GenericRegistryAdapterImpl.java` | Modified | Source dispatch, bare flowRef, no provider segment |
| `sdmx-proxy/.../services/adapter/MatchAllKeyExpander.java` | New | Smallest-codelist OR key |
| `sdmx-proxy/.../services/adapter/AdapterRouterImpl.java` | Modified | `expandMatchAllKey` in `fetchData` and the bypass branch; `BaseException` rethrow in `getStructuresConversion`; repair fixture on the probe |
| `sdmx-proxy/src/test/.../GenericRegistryAdapter21Test.java`, `GenericRegistryAdapterImplTest.java`, `AdapterRouterImplTest.java` | Modified | Constructor calls gain the new dependencies |
| `sdmx-proxy/.../services/fixture/structure/FillMissingRepresentationsXmlFixture.java` | New | `R1` |
| `sdmx-proxy-e2e/.../registry/STATCAN_2_1_RegistryTestSuit.java` | New | Suite |
| `sdmx-proxy-e2e/.../registry/statcan/2_1/statcan_2_1_registry_config.json` | New | Registry config |
| `sdmx-proxy-e2e/.../registry/statcan/2_1/statcan_2_1_test_config.json` | New | Test config |
| `sdmx-proxy-e2e/.../framework/config/QueryShapeTestSuitConfiguration.java` | Modified | Five fields (`unfilteredData`, `longOrKey`, `longOrKeyDataflowUrn`, `expectEmptyAvailabilityForEmptyTimeRange`, `jsonSeriesKeyFormat`) |
| `sdmx-proxy-e2e/.../framework/config/DsdFidelityTestSuitConfiguration.java` | Modified | Three fields |
| `sdmx-proxy-e2e/.../framework/BaseRegistryTestSuite.java` | Modified | Six pins |
| Unit tests and fixtures under `sdmx-proxy/src/test/` | New | See Verification |

## Edge cases

1. **A table StatCan doesn't know.** `structure/Data_Structure_99999999` returns `404`. The source
   passes it on as `404`. A catalog entry whose bundle 404s fails only when it's requested.
2. **An archived table requested directly.** `DF_10100001` isn't in the catalog by default, but its
   bundle exists (`200`). The source serves it: the catalog filter is a catalog filter, not an
   access rule.
3. **The OR key passes the URL limit.** StatCan answers `414` above roughly 8,000 characters, which
   is about 1,700 codes of four characters. The smallest codelist in both probed tables has 3
   codes. If StatCan answers `414`, the proxy passes the error on. Splitting the request is out of
   scope; the follow-up would reuse the emulator's key decomposition.
4. **The whole dataflow is large.** `.1+2+3.` on 17100005 returned 14 MB in 14 seconds.
   Unfiltered data and the unfiltered availability probe both download that much. Only
   `maxProbeBytes` (256 MB default) and `maxProbeSeries` bound the probe; no main code reads
   `probeSplitThresholdBytes` or `probeTimeoutMillis`. The probe runs under the registry
   `readTimeout`, 120 seconds in step 12.
5. **Two components share a concept with different representations.** The first wins, with a
   `WARN` log (step 11).
6. **A client still requests `CL_Geography`.** Without a table number, `tableOf` returns empty and
   the proxy answers `404`. This is deliberate: the bare ID is ambiguous.
7. **The 409 update window.** `maxAttempts: 3` gives two retries, after 2 and 4 seconds. A table locked for longer
   still fails with `409`. E2E runs that hit the window can fail. Schedule nightly runs outside
   midnight to 8:30 a.m. Eastern time.
8. **A WDS outage.** The catalog fails, so `dataflow/StatCan/all` fails, but single-table requests
   still work from the SDMX service. The catalog stays in the cache until its TTL runs out.
9. **StatCan's default response lies about its type.** Without an `Accept` header, StatCan sends a
   `GenericData` body labelled `structurespecificdata+xml`. The proxy always sends `Accept`, so this
   doesn't reach it.
10. **A structure-specific `message:ID` contains quotes**, as in `DS"3554675123611"`. It's text
    content, legal XML, and the reader ignores it. A unit test keeps a fixture with it.
11. **A client sends `limit`.** The proxy drops it: `getData21` doesn't send it, and with
    `supportsLimit: true` the router doesn't emulate it. The validator rule that pairs emulated
    availability with `supportsLimit: true` forces this. StatGPT doesn't send `limit`.
12. **Parallel first requests for one table.** Each downloads the bundle, because cache misses
    aren't single-flighted. The cost is bounded by the number of concurrent requests.

## Verification

### Unit tests

Fixtures come from the probes, saved under `sdmx-proxy/src/test/resources/statcan/`:
`Data_Structure_17100005.xml`, `Data_Structure_18100004.xml`, `getAllCubesListLite-sample.json`
(about 20 entries with both `archived` values), and `DF_17100005_1.1.1_ss.xml`.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/structure/statcan/StatCanIdentifiersTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldResolveTableFromEachPattern()` | `DF_`, `Data_Structure_`, `CL_{table}_`, `CONCEPTS_` all yield `17100005` |
| `shouldRejectBareCodelistId()` | `CL_Geography` yields empty (edge case 6) |
| `shouldRejectNonEightDigitTable()` | `DF_1710000` yields empty |

**File:** `.../statcan/StatCanBundleRenamerTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldRenameCodelistsAndReferences()` | Every `Codelist` ID and every `Ref class="Codelist"` gains the table prefix |
| `shouldRenameConceptSchemeAndConceptRefs()` | `CONCEPTS` and every `maintainableParentID` become `CONCEPTS_17100005` |
| `shouldRewriteUrns()` | Renamed URNs parse with sdmx-core |
| `shouldFailWhenDsdIdDoesNotMatchTable()` | Table `18100004` with the 17100005 bundle throws |
| `shouldProduceDisjointUrnsForTwoTables()` | Parsed 17100005 and 18100004 bundles share no codelist URN |

**File:** `.../statcan/StatCanCatalogWriterTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldWriteCurrentTablesOnly()` | `archived == "1"` entries are absent |
| `shouldIncludeArchivedWhenConfigured()` | `includeArchived` keeps them |
| `shouldWriteEnglishAndFrenchNames()` | Both `com:Name` elements, escaped |
| `shouldParseWithSdmxCore()` | The output parses to `SdmxBeans` with one dataflow per kept entry, each referencing `Data_Structure_{table}` |

**File:** `.../statcan/StatCanStructureSourceTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldReturnCatalogForAllDataflows()` | `dataflow/*/*/latest` returns the catalog |
| `shouldReturnDataflowWithDsdForDatastructureReference()` | Dataflow plus DSD, nothing else |
| `shouldReturnOneCodelist()` | `codelist/StatCan/CL_17100005_Geography/1.0` returns 15 codes |
| `shouldReturnConceptSchemeWithCoreRepresentations()` | Selected alone, the scheme still carries the copied representations |
| `shouldDownloadBundleOncePerTable()` | Four requests for one table call `getBundle` once |
| `shouldAnswer501ForAllCodelists()` | `codelist/StatCan/*` throws `StructureListNotSupportedException` |
| `shouldAnswer404ForUnknownVersion()` | Version `2.0` throws `StructureNotFoundException` |
| `shouldTreatNullReferencesAsNone()` | A codelist request without `references` returns the codelist alone |
| `shouldTreatAncestorsAsParents()` | `ancestors` on a codelist returns the codelist plus the DSD |
| `shouldFailFastOnNonXmlReturnFormat()` | Return format `JSON_STRUCTURE_2_0_0` throws `UnexpectedStateException` |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/structure/FillMissingRepresentationsXmlFixtureTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldGiveObsValueStringRepresentation()` | `OBS_VALUE` gains `TextFormat textType="String"` |
| `shouldCopyEnumerationToConcept()` | Concept `Geography` gains the `CL_17100005_Geography` enumeration |
| `shouldCopyTextFormatToConcept()` | Concept `VECTOR_ID` gains `String` |
| `shouldGiveTimeDimensionObservationalTimePeriod()` | `TIME_PERIOD` component and concept |
| `shouldBeIdempotent()` | Applying twice equals applying once, byte for byte |
| `shouldKeepExistingRepresentations()` | An OECD DSD fixture passes through unchanged |
| `shouldLetComponentInheritConceptRepresentation()` | A concept with a `Double` `CoreRepresentation` and a component without `LocalRepresentation`: the component stays without one |
| `shouldCopyFirstRepresentationOnConflict()` | Two components with different representations on one concept: the first wins, and a `WARN` is logged (edge case 5) |
| `shouldAcceptNullConfig()` | `apply(stream, null)` works |
| `shouldLeaveDsdAloneWithoutConcepts()` | A DSD without its concept scheme in the document passes through unchanged |
| `shouldLeaveConceptSchemeAloneWithoutDsd()` | A concept scheme alone passes through unchanged |
| `shouldPreserveChildOrder()` | Output validates against the 2.1 XSDs in `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/resources/xsd/2_1/` |

Input for the copy tests is the renamed bundle (renamer output), so the expected enumeration is
`CL_17100005_Geography`.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/data/RepairEmptyDataSetXmlDataFixtureTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldDropStraySeriesEndTagStructureSpecific()` | The probed empty body becomes well-formed and parses to zero series |
| `shouldDropStraySeriesEndTagGeneric()` | Same for `</generic:Series>` |
| `shouldPassThroughNonEmptyData()` | `DF_17100005_1.1.1_ss.xml` is unchanged, byte for byte |
| `shouldPassThroughPastBufferCap()` | A header over 64 KB passes through unchanged |
| `shouldNotMatchHeaderDataSetElements()` | A header with `message:DataSetID` and `message:DataSetAction` still gets the repair on the real dataset tag |
| `shouldRunRepairFirst()` | In `DataFixtureServiceTest`, the repair runs before a fixture listed ahead of it |
| `shouldConvertRepairedEmptyBodyToJson20()` | The repaired probed body, structure-specific and generic, converts to a well-formed empty JSON 2.0 data message |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/availability/ProbeQueryBuilderTest.java` (Modified) and `QueryTranslatorImpl` tests

| Test method | Description |
|-------------|-------------|
| `shouldCopyTimeWindowToProbe()` | `startPeriod` and `endPeriod` reach the probe |
| `shouldDeriveTimeWindowForEmulated21Availability()` | A `c[TIME_PERIOD]=ge:2020+le:2021` filter yields `2020` and `2021` |
| `shouldNotDeriveTimeWindowForRealAvailability()` | A 2.1 registry without emulation keeps `null`, and so does one with emulation configured and `availabilityEnabled: true` |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/MatchAllKeyExpanderTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldEnumerateSmallestCodelist()` | 17100005 yields `*.2+3+1.*`: `CL_Gender`, codes in codelist order |
| `shouldBreakTiesByPosition()` | Two equal codelists pick the earlier dimension |
| `shouldFailWithoutEnumeratedDimension()` | Throws `UnexpectedStateException` |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapter21Test.java` (Modified)

| Test method | Description |
|-------------|-------------|
| `shouldSendBareFlowRefWhenConfigured()` | `flowRefIdOnly` yields `DF_17100005` |
| `shouldOmitProviderRefWhenConfigured()` | `omitProviderRef` calls the `GET /{flowRef}/{key}` overload with the query map |
| `shouldKeepThreePartFlowRefForAvailability()` | With `flowRefIdOnly` set, `getAvailability21` still sends `agency,id,version` |
| `shouldKeepThreePartFlowRefByDefault()` | The OECD expectation stays |
| `shouldDispatchStatCanStructureSource()` | `STATCAN` never calls `Sdmx21StructureClient` |

**File:** `AdapterRouterImpl` tests (Modified)

| Test method | Description |
|-------------|-------------|
| `shouldExpandMatchAllKeyForData()` | Key `*` reaches the adapter as the OR key |
| `shouldExpandMatchAllKeyForAvailabilityProbe()` | Unfiltered `PROBE` emulation sends the OR key |
| `shouldNotExpandNarrowedKey()` | `1.*.*` passes through |
| `shouldExpandMatchAllKeyWithTimeOnlyFilter()` | Key `all` with a `TIME_PERIOD` entry in `filters` still expands |
| `shouldExpandMatchAllKeyOnBypass()` | The bypass branch sends the OR key |
| `shouldAnswer404ForUnknownStatCanArtefact()` | `StructureNotFoundException` thrown by the adapter inside `getStructuresConversion` reaches the client as `404`, not `500` |
| `shouldAnswer501ForStatCanCodelistList()` | Same for `StructureListNotSupportedException` and `501` |
| `shouldKeep429ForRateLimitOnStructureConversion()` | `RateLimitExceededException` on that path now reaches the client as `429` |
| `shouldKeep501ForUnsupportedConversion()` | `UnsupportedConversionException` now reaches the client as `501` |
| `shouldRepairEmptyDataSetOnProbe()` | The probe context applies `REPAIR_EMPTY_DATASET` and no other data fixture |

Also add `Resilience4jComponentFactory` tests for a retried `409` and a non-tripping circuit
breaker, and `ConfigValidator` tests for the three `STATCAN` rules.

### Manual verification

Start the proxy with the StatCan config, then run the following requests:

```bash
./gradlew :sdmx-proxy:bootRun
P=http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" "$P/structure/dataflow/StatCan/*/latest"
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" "$P/structure/codelist/StatCan/CL_17100005_Geography/1.0"
curl -s -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" "$P/data/dataflow/StatCan/DF_17100005/latest/*"
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" "$P/availability/dataflow/StatCan/DF_17100005/latest/*"
```

The prefix comes from `WebMvcSettings.API_PREFIX`. Expected results:

1. `200`, about 5,083 dataflows.
2. A codelist with 15 codes.
3. Every series of 17100005; the proxy log shows `data/DF_17100005/.2+3+1.` with no provider
   segment.
4. A constraint listing every `Geography`, `Gender`, and `Age_group` code present in the data.

### E2E tests

Run the StatCan suite with the `e2e-report` skill:

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.STATCAN_2_1_RegistryTestSuit"
```

Run the BIS, IMF, ESTAT, and OECD suites too. Steps 3, 9, and 10 touch shared code, and every new
pin must skip for registries that don't set its field.

## Open questions

1. **`OBS_VALUE` type.** The decision gives it `String`. StatCan's values are numeric, and `Double`
   would let validators check them. Changing it later is a one-line fixture config, if the fixture
   takes an optional `measureTextType`.
2. **Time window on real 2.1 availability.** `translateAvailabilityQuery` drops a `c[TIME_PERIOD]`
   filter for every 2.1 registry, including IMF 2.1 and OECD, which have a real availability
   endpoint. Step 11b fixes only the emulated case. Deriving `startPeriod` and `endPeriod` for all
   2.1 registries is more correct, but it changes what those registries receive and needs their
   E2E suites. Decide whether to widen it here or in a follow-up.
3. **Production config location.** Resolved in review iteration 1:
   `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` (step 12).

## SDMX standard references

- SDMX-REST data resource and `flowRef`: `sdmx-rest-2.2.0/doc/data.md`
- SDMX-JSON series key format: `sdmx-json-2.0.0/data-message/docs/1-sdmx-json-field-guide.md`
- Representation defaults in sdmx-core:
  `sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/base/RepresentationBeanImpl.java`,
  `sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/datastructure/DimensionBeanImpl.java`
- 2.1 structure reader: `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/factory/structure/SdmxMLStructureReaderFactory.java`
- JSON 1.0 data reader: `sdmx-core-2.3.9/fusion-sdmx-json/src/main/java/io/sdmx/format/json/engine/data/reader/SdmxJsonDataReaderEngine.java`

## Revision 1: registry-level adapter

Status: Implemented, 2026-10-26. This revision replaces the `structureSource` part of steps 1, 2, and 8.
Everything else stays as implemented.

### Why

Steps 1 and 8 put a StatCan switch in two generic places. `StructureEndpointConfiguration` gained
`structureSource.type: STATCAN`, and `GenericRegistryAdapterImpl.getStructures21` gained a branch
for it. StatCan isn't an SDMX registry with a quirk in one endpoint: it lacks most of the
structure API. The proxy core should say so once, at the registry level, and keep the generic
SDMX path free of StatCan code.

### What changes

The following diagram shows the new adapter layout:

```
AdapterRouterImpl, LimitEmulationService, AvailabilityProber, ...   (inject GenericRegistryAdapter)
        |
        v
RoutingRegistryAdapter  (@Primary, implements GenericRegistryAdapter)
        |  registry.adapter
        +-- SDMX (default) --> GenericRegistryAdapterImpl      (no StatCan code)
        +-- STATCAN -------> StatCanRegistryAdapter
                                getStructures  -> StatCanStructureSource
                                getData        -> GenericRegistryAdapterImpl
                                getAvailability-> GenericRegistryAdapterImpl
```

No caller changes. Every consumer already injects the `GenericRegistryAdapter` interface, and
the `@Primary` router is what Spring hands them.

**Config** (`sdmx-proxy-config`):

- Remove `StructureSourceType`, `StructureSourceConfiguration`, and
  `StructureEndpointConfiguration.structureSource`.
- Add `RegistryAdapterType {SDMX, STATCAN}` and two fields on `RegistryConfiguration`:
  - `adapter`, which defaults to `SDMX`;
  - `adapterConfig`, a `Map<String, String>` in the same shape as `FixtureConfiguration.config`.
- For `STATCAN`, `adapterConfig` carries two keys:
  - `catalogUrl`, required;
  - `includeArchived`, which defaults to `false`.
- The data flags `flowRefIdOnly`, `omitProviderRef`, and `expandMatchAllKey` and both fixtures
  stay where they are. They describe generic SDMX 2.1 deviations, and the StatCan adapter
  delegates data to the generic adapter, which applies them.

```json
{
  "name": "StatCan",
  "adapter": "STATCAN",
  "adapterConfig": {"catalogUrl": "https://www150.statcan.gc.ca/t1/wds/rest/", "includeArchived": "false"},
  "versions": {"SDMX_2_1": {"structureEndpointConfig": {"supportedFormats": ["XML_STRUCTURE_2_1"], "fixtures": [{"type": "FILL_MISSING_REPRESENTATIONS"}]}}}
}
```

**Code** (`sdmx-proxy`):

| File | Change |
|------|--------|
| `services/adapter/RoutingRegistryAdapter.java` | New. `@Primary`; picks the adapter by `query.getRegistryConfiguration().getAdapter()`; null means `SDMX` |
| `services/adapter/statcan/StatCanRegistryAdapter.java` | New. Structures go to `StatCanStructureSource`; data and availability go to `GenericRegistryAdapterImpl` |
| `services/structure/statcan/*` | Moved to `services/adapter/statcan/`. `StatCanStructureSource` reads `catalogUrl` and `includeArchived` from `adapterConfig` |
| `services/adapter/GenericRegistryAdapterImpl.java` | Drop the STATCAN branch and the `StatCanStructureSource` dependency. Constructor back to two arguments |
| `registry/api/SdmxApiClientProvider(Impl).java` | Drop `getStatCanStructureClient` and `getStatCanWdsClient`. Add a generic `<T extends SdmxApiBase> T getClient(Class<T> clientClass, String baseUrl, RegistrySelectionResult registry)` over the existing `getOrBuildClient`, so the client cache, resilience, and timeouts stay shared |
| `registry/api/client/StatCan*Client.java` | Moved to `services/adapter/statcan/`, next to their only user |

**Validation**: the `ConfigValidator` rules and the runtime fail-fast in the source move to the
adapter:
- `STATCAN` requires `adapterConfig.catalogUrl`;
- every version must be `SDMX_2_1`;
- the structure `supportedFormats` must be exactly `[XML_STRUCTURE_2_1]`.

**Docs**: in the README, replace the `structureSource` rows with `adapter` and `adapterConfig`.
`HOW_IT_WORKS.md` gets the new adapter diagram.

### Tests

- `RoutingRegistryAdapterTest`:
  - `SDMX` and null go to the generic adapter;
  - `STATCAN` structures go to the StatCan adapter;
  - `STATCAN` data goes through the generic adapter.
- `GenericRegistryAdapter21Test`: drop `shouldDispatchStatCanStructureSource` and the third
  constructor argument.
- `StatCanStructureSourceTest`: reads the catalog settings from `adapterConfig`.
- `ConfigValidatorTest`: the three rules on `adapter: STATCAN`.
- E2E: the StatCan registry configs use `adapter` and `adapterConfig`. Behavior doesn't change,
  so the suite expectations stay the same.

## Review Log

### Iteration 1 — 2026-10-26

Two critics ran in parallel: one on steps 1-10, one on steps 11-15 and Verification. Their
duplicate findings are merged. A live probe on 2026-10-26 confirmed finding 1.

**Critic findings (verified):**

1. **[Critical] B4, Step 9 / `GenericRegistryAdapterImpl.java:122`, `Sdmx21DataClient.java:28`** — `getData21` always appends `/all` as the provider segment, and StatCan answers `404` to it.
   - Evidence: `providerRef = "all"` feeds `GET /{flowRef}/{key}/{providerRef}`. Probe: `data/DF_17100005/1.1.1/all` and `.../StatCan` return `404` with both XML 2.1 `Accept` values. `data/DF_17100005/1.1.1` returns `200`, and the `Content-Type` matches the request. The critic rated this Medium (unprobed). The probe raised it to Critical.
   - Resolution: **Accepted** — new `omitProviderRef` flag, a new `Sdmx21DataClient` overload `GET /{flowRef}/{key}` with `@QueryMap`, probe rows added, config and tests updated.
2. **[High] Step 4, Step 7 / `AdapterRouterImpl.java:247-253`** — `getStructuresConversion` wraps every non-Feign exception in `StructureConversionException` (`500`), so the source's `404` and `501` reach the client as `500`.
   - Evidence: read the catch blocks. StatCan has bypass off, so every structure request takes this path.
   - Resolution: **Accepted** — rethrow `BaseException` before the generic catch. Router-level status tests added.
3. **[High] Step 7 / `exception/NotImplementedException.java:7`** — `NotImplementedException` is abstract and has protected constructors.
   - Evidence: read the class. It has three concrete subclasses.
   - Resolution: **Accepted** — new `StructureListNotSupportedException`.
4. **[High] Step 10 / `QueryTranslatorImpl.java:582`** — the `MapUtils.isEmpty(query.getFilters())` guard skips the expansion for key `*` plus a time filter, which is StatGPT's normal request. StatCan answers `400`.
   - Evidence: `.filters(filters)` keeps the original map after the 2.1 key folding. Both critics found this one.
   - Resolution: **Accepted** — the decision rests on the key alone. `emptyTimeRange` pin and `shouldExpandMatchAllKeyWithTimeOnlyFilter` added.
5. **[High] Step 11 / `SDMXStructureDataStructure.xsd` (`PrimaryMeasureType`)** — writing `String` on a component without `LocalRepresentation` overrides an inherited concept representation, so the fixture isn't generic.
   - Evidence: the XSD text says a component may take its representation from the concept.
   - Resolution: **Accepted** — the component stays unchanged when its concept has a `CoreRepresentation`. Inheritance test added.
6. **[Medium] Step 11 collect pass** — collecting only non-empty local representations sends `TIME_PERIOD`'s concept to `String`, which contradicts the test.
   - Resolution: **Accepted** — the collect pass records effective representations.
7. **[Medium] Step 7 selection table / `SdmxStructure30Controller.java:51`** — there are no rows for null `references` (what StatGPT sends) or for `ancestors` (in the E2E matrix).
   - Resolution: **Accepted** — null counts as `none`, `ancestors` as `parents`, and unlisted type names add nothing. Tests added.
8. **[Medium] Step 1, Step 8 / `ConfigValidator` usage** — the validator runs only in the config server, not on the proxy's own config load or `POST /config`.
   - Evidence: grep finds it only under `sdmx-proxy-config-server`.
   - Resolution: **Accepted** — the validator section says so, the source fails fast at runtime, and the format rule is listed as the third rule.
9. **[Medium] Step 10 bypass routing / `AdapterRouterImpl.java:376-383`** — routing bypass through `fetchData` would leak `RegistryNoDataException` as `500` for other registries.
   - Resolution: **Accepted** — the bypass branch calls only `expandMatchAllKey(query)`.
10. **[Medium] Step 14 / `BaseRegistryTestSuite.java:545`** — the DSD fidelity request uses `references=none`, so a representation pin on it never sees concepts.
    - Resolution: **Accepted** — the pin requests `references=children`, and `expectedConceptEnumerations` was added.
11. **[Medium] Step 12** — `supportsLimit: true` silently drops a client's `limit` on 2.1. The claim that StatCan behaves like Eurostat was wrong, because Eurostat is 3.0.
    - Evidence: `getData21` has no `limit` parameter.
    - Resolution: **Accepted** — recorded as edge case 11.
12. **[Medium] B5, edge case 4** — the whole-table size figures contradicted each other.
    - Evidence: the 354,498-byte file is `.1.` (one `Geography`). A re-probe of `.1+2+3.` returned 14,012,593 bytes.
    - Resolution: **Accepted** — figures corrected.
13. **[Low] Step 13 / `StructureTypeAndUrn`, `QueryShapeTestSuitConfiguration`** — the test-config sketch didn't match the framework's shapes.
    - Resolution: **Accepted** — the table now uses `{type, urn}`, `dataflowUrn`, `mediaTypes`, and `registryReturnFormats`, and the availability matrix is kept small.
14. **[Low] Step 12, open question 2** — the production config is `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`.
    - Resolution: **Accepted** — added to step 12 and Files affected, and the question is closed.
15. **[Low] Step 10 / `AvailabilityQueryCanonicalizer.isWildcardKey`** — the match-all rule already exists.
    - Resolution: **Accepted** — the canonicalizer is reused, and the normalizer change is dropped.
16. **[Low] Step 11** — the time-rule rationale was inaccurate, the `LocalRepresentation` insertion point was unspecified, and three tests were missing: renamer input, conflict, and null config.
    - Resolution: **Accepted** — all of them are fixed.
17. **[Low] B1 table, Step 15** — StatGPT's availability call is a `POST`, and it swallows `400`/`404` into empty constraints.
    - Resolution: **Accepted** — the table is corrected, and step 15 checks for the log line.
18. **[Low] README scope, constructor call sites, `parseStructures` signature, `X-Source-Artefact-Urn` wording, worked-example key order (`2+3+1`)**
    - Resolution: **Accepted** — all of them are fixed.
19. **[Low] Concurrent cache misses aren't single-flighted.**
    - Resolution: **Deferred** — one extra download per race. Recorded as edge case 12.

**Discarded (unverified):** none.

### Iteration 2 — 2026-10-26

One critic reviewed the changes from iteration 1. A live probe on 2026-10-26 turned finding 4 into a
new Critical (finding 1 here).

**Critic findings (verified):**

1. **[Critical] New `B7` / live probe** — every StatCan empty result is malformed XML: `<message:DataSet ...></Series> </message:DataSet>` (`</generic:Series>` in generic).
   - Evidence: `data/DF_17100005/.2+3+1.?startPeriod=1800&endPeriod=1801` and `data/DF_17100005/999.1.1` return `200` with that body, in both XML 2.1 formats. A Python XML parse fails with "mismatched tag". The probe context (`AdapterRouterImpl.RouterAvailabilityEmulationContext.fetchData`) applies no data fixtures.
   - Resolution: **Accepted** — step 11a adds the streaming `REPAIR_EMPTY_DATASET` data fixture and applies it on the probe through an `onlyTypes` overload. E2E key `999.1.1` and the `emptyTimeRange` pin cover it.
2. **[High] B5, step 5 of the worked example / `ProbeQueryBuilder.build`, `QueryTranslatorImpl.translateAvailabilityQuery`** — on SDMX 2.1 the availability probe drops the time filter.
   - Evidence: `build` sets `.key(key).filters(filters)` and never sets the period. `translateAvailabilityQuery` sets `startPeriod` only from the controller, which passes `null` on POST. `getData21` ignores `filters`.
   - Resolution: **Accepted** — new `B8` section and step 11b: derive the window for emulated 2.1 availability, and copy it in `ProbeQueryBuilder`. Tests and the `timeWindowedAvailability` pin are added. Widening the fix to real 2.1 availability (IMF 2.1, OECD) became open question 2.
3. **[Medium] Step 11** — the fixture wrote `String` when a component's concept was absent from the document, and it filled a concept scheme fetched alone differently from one fetched with its DSD.
   - Resolution: **Accepted** — the fixture leaves a document unchanged unless both sides are present. Two tests are added.
4. **[Medium] Step 4 / `AdapterRouterImpl.java:247-253`** — the `BaseException` rethrow changes statuses for every registry: `429`, `501`, and `503` instead of `500`.
   - Evidence: grep found `RateLimitExceededException`, `UnsupportedConversionException`, and `CacheUnavailableException` throw sites on that path. No test pins `500`.
   - Resolution: **Accepted** — the design states the change as intended, and two tests are added.
5. **[Medium] Step 13 `emptyTimeRange`** — StatCan's empty-range answer had never been probed.
   - Resolution: **Accepted** — probed: `200` with the malformed body. That became finding 1.
6. **[Medium] Step 9 / `GenericRegistryAdapterImpl.java:120,208`** — `getFlowRef` is shared by the data and availability paths.
   - Resolution: **Accepted** — the branch moves to `getData21`, and an availability test is added.
7. **[Low] Step 4** — `StructureNotFoundException` broke the per-status exception family convention.
   - Resolution: **Accepted** — an abstract `NotFoundException` is added.
8. **[Low] Edge case 7 / `Resilience4jComponentFactory.java:87-96`** — the retry timing was wrong.
   - Resolution: **Accepted** — two retries, after 2 and 4 seconds.
9. **[Low] Step 10** — the `firstDataStructure` helper doesn't exist.
   - Resolution: **Accepted** — `expand(SdmxBeans)` looks up the DSD itself.
10. **[Low] Step 14** — three gaps: the JSON 2.0 series path, the short URN against the full URN, and a `longOrKey` of about 600 characters that never exercises the URL limit.
    - Resolution: **Accepted** — paths are given per version, the check uses `endsWith`, and a long-URL table is chosen at implementation with a minimum of 5,000 characters asserted.
11. **[Low] E2E cost** — key `*` in the pairwise data matrix downloads 14 MB per run.
    - Resolution: **Accepted** — `*` is removed from `dataflowConfigs`, and the dedicated pin covers it.

**Discarded (unverified):** none.

### Iteration 3 — 2026-10-26

One critic reviewed the iteration 2 changes. It confirmed that the repair runs before any parse on
the data path (`AdapterRouterImpl.getData`, around lines 395-429) and on the probe path (before
`XmlSeriesKeyHarvester`). It also confirmed that `translateAvailabilityQuery` has the beans and
`dimensionService` that B8 needs, and that the emulated-availability cache key includes the
filters.

**Critic findings (verified):**

1. **[High] Step 14 `timeWindowedAvailability` / `AvailabilityEmulationConfiguration.java:65`, `XmlSeriesKeyHarvester`** — the pin asserted a `TIME_PERIOD` range, but the emulated constraint never contains one.
   - Evidence: `includeTimePeriod = false`, and the harvester collects only non-time dimensions.
   - Resolution: **Accepted** — the pin is replaced by the gated `expectEmptyAvailabilityForEmptyTimeRange`, which extends `testAvailabilityWithEmptyTimeRangeReturnsOk`. It asserts empty key values, which also covers B7 on the probe path.
2. **[Medium] Step 11a** — a prefix match on `message:DataSet` can hit the header elements `DataSetID` and `DataSetAction`.
   - Resolution: **Accepted** — the match is now on the exact local name, and a test is added.
3. **[Medium] Step 11b / `AdapterRouterImpl.java:547`** — the derivation condition differed from the router's dispatch (`!isAvailabilityEnabled()`).
   - Resolution: **Accepted** — the condition matches the dispatch, and the test covers emulation together with `availabilityEnabled: true`.
4. **[Medium] Step 1 README** — the data fixture table needs a `REPAIR_EMPTY_DATASET` row.
   - Resolution: **Accepted** — the row is added.
5. **[Low] B5, edge case 4** — no main code reads `probeSplitThresholdBytes` or `probeTimeoutMillis`.
   - Evidence: grep finds no getter use.
   - Resolution: **Accepted** — the text names `maxProbeBytes`, `maxProbeSeries`, and the registry `readTimeout`.
6. **[Low] Step 11a** — fixture order: a StAX fixture listed first would fail on the malformed body.
   - Resolution: **Accepted** — the service always runs the repair first, and a test pins it.
7. **[Low] Step 11a** — the conversion of the repaired empty body wasn't unit-tested.
   - Resolution: **Accepted** — `shouldConvertRepairedEmptyBodyToJson20` is added.

**Discarded (unverified):** none.

**Exit:** iteration 3 produced one High finding, so the loop continues to iteration 4.

### Iteration 4 — 2026-10-26

A final pass over the whole design. The critic traced the extended availability pin end to end.
The test sends a GET, the key normalizes to `all`, the probe carries the derived window, and the
key is expanded. The harvester returns empty sets for a body with no series, and
`AvailabilityConstraintSynthesizer` writes one cube region whose keys have empty values. The
response is `200`, so the pin can be implemented.

**Critic findings (verified):**

1. **[Medium] No changes required** — `QueryTranslatorImpl.java` was listed as unchanged, which contradicts step 11b.
   - Resolution: **Accepted** — the entry is reworded.
2. **[Low] Step 14** — the empty-availability pin could pass with no constraint at all.
   - Resolution: **Accepted** — the pin also asserts one key per non-time dimension.
3. **[Low] Step 13 / `QueryTranslatorImpl.java:56-57`** — the `emptyTimeRange` value was unspecified, and only `ge`, `gt`, `le`, and `lt` derive a period.
   - Resolution: **Accepted** — the value is `ge:1800+le:1801`.
4. **[Low] Files affected** — the `GlobalExceptionHandler` row disagreed with step 4.
   - Resolution: **Accepted** — the row now matches step 4.
5. **[Low] Step 14 / `BaseRegistryTestSuite.java:~1030`** — a query string embedded in the path can be dropped silently.
   - Resolution: **Accepted** — the pins pass parameters through the params map.

**Discarded (unverified):** none.

**Exit:** converged. Iteration 4 produced no Critical or High findings. It is also the iteration cap.
