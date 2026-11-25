# Design 044: Structure cache preheat for slow registries

**Status:** draft. Supersedes [design 021](../2026-05-22-021-cache-prehit/design.md), which rejected
prewarm. The OECD cold-start measurements in this document meet revisit criteria 1 and 2 of
design 021: natural traffic doesn't warm the cache before clients time out, and the cold
penalty is minutes. Criterion 3 (REDIS mode) doesn't hold. This design stays cheap in LOCAL mode
because, with descendants splitting, a full prewarm costs about 38 OECD calls per replica.

This is phase 1 of a two-phase plan:

1. **Phase 1, this design.** The list of dataflows to prewarm is maintained by hand in the proxy
   configuration.
2. **Phase 2, a separate design.** StatGPT admin writes the same configuration section when
   datasets are created, deleted, or changed. The phase 1 schema is shaped so that phase 2
   changes only who fills the list.

## Context

OECD (`https://sdmx.oecd.org/public/rest/`) is an SDMX 2.1 registry that caps each client IP at
about 20 requests per minute and answers bursts with HTTP 429. Design 042 added single-flight, so
concurrent requests for one structure share one upstream call. Single-flight can't reduce the
number of *distinct* structures that a cold StatGPT load asks for, and that number is the
problem.

### Evidence: a cold StatGPT admin load

A proxy log was captured while StatGPT admin opened its 19 OECD datasets against a cold proxy
(2026-11-17, 13:27:50 to 13:37:13). The log file is `proxy_logs_123123123123` at the repository
root (UTF-16, not committed).

| Metric | Value |
|---|---|
| Wall-clock time for the cold load | about 9.5 minutes |
| OECD structure calls | 184, all with distinct keys |
| Of which codelist / dataflow / concept scheme | 128 / 38 / 18 |
| HTTP 429 answers from OECD | 500 |
| Calls merged by single-flight | 13 |
| Client disconnects (`Broken pipe`) | 41 |

StatGPT sends the following structure requests to the proxy, all with
`Accept: application/vnd.sdmx.structure+xml;version=2.1`. This matches
`statgpt-backend/statgpt/common/data/sdmx/v21/dataflow_loader.py`.

| Request | Count | Raw key suffix |
|---|---|---|
| `structure/dataflow/{agency}/{id}/latest?references=datastructure&forceDataflowDynamicAnnotations=true` | 19 distinct | `{agency}:{id}:latest:datastructure:full` |
| `structure/conceptscheme/{agency}/{id}/{version}` | 18 distinct | `{agency}:{id}:{version}::full` |
| `structure/codelist/{agency}/{id}/{version}` | 128 distinct | `{agency}:{id}:{version}::full` |

Codelist and concept scheme requests carry `X-Source-Artefact-Urn` with the DSD URN. That header
is what routes codelists of agency `SDMX` (for example `SDMX:CL_FREQ(2.1)`) to the OECD registry.
The proxy ignores `forceDataflowDynamicAnnotations`: no API method declares it, and it is in no
cache key.

The proxy itself sends one more structure request per dataflow. The availability controller
calls `getSdmxBeans` with `dataflow/{agency}/{id}/{resolved version}?references=descendants&detail=full`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/AvailabilityQuery30Controller.java:137-149`).
**The response to this request already contains every concept scheme and codelist of the
dataflow.** StatGPT then asks for each of them separately, and each one is another OECD call.

An OECD check from the same investigation confirms that splitting is lossless at the registry
level. The `CL_VALUATION` codelist inside the response to
`dataflow/OECD.SDD.NAD/DSD_NAMAIN10@DF_TABLE1/latest?references=descendants` is byte-identical
(9,253 bytes) to the `<structure:Codelist>` element of a direct
`codelist/OECD.SDD.NAD/CL_VALUATION/1.0` request.

## Problem

- **P1: Distinct-structure stampede.** A cold load sends 184 distinct OECD structure calls. At
  about 20 calls per minute, that is at least nine minutes, with hundreds of 429 retries. StatGPT
  gives up on many requests before the proxy answers.
- **P2: The proxy fetches the same artefacts twice.** For every dataflow, the proxy fetches
  `references=descendants`, which holds all concept schemes and codelists, and then fetches
  those artefacts again one by one. 146 of the 184 calls fetch data that's already in the raw
  cache.
- **P3: No warm state survives a restart or a configuration change.** `CACHE_MODE=LOCAL` loses
  everything on restart. `CacheService.invalidateAll()` runs on every configuration change, from
  the config server (`ConfigServerConfigExtractor.pollForUpdate`) or from `POST /config`.
- **P4: Entries expire under load.** Raw entries live 24 hours. Nothing refreshes them, so the
  stampede repeats a day after the last cold load.
- **P5: Empty answers are refetched every time.** When a registry answers HTTP 204 (for example
  for a removed artefact version), `RawStructureFetcherImpl` returns an empty array and caches
  nothing, so every request goes upstream again. The design 042 E2E run showed 21 such repeat
  calls for `IMF.STA:IRFCL(8.0.0)`.

## Current architecture

The following diagram shows the structure path after design 042:

```
GET /structure/{type}/{agency}/{id}/{version}  (Accept, X-Source-Artefact-Urn)
  SdmxStructure30Controller
    QueryTranslator.translateStructureQuery(type, agency, id, version, references, detail, accept, sourceUrn)
    AdapterRouterImpl.getStructures(query)
      ready-response cache  key = response:{raw key}:{md5("Accept:" + contentType)}
        miss -> getStructuresConversion
                  RawStructureFetcherImpl.fetch(query)
                    raw cache  key = structure:{type}:{registry}:{sdmxVersion}:{returnFormat}:{md5(structure URL)}:{agency}:{id}:{version}:{references}:{detail}
                      miss -> cooldown check -> RequestCoalescer.coalesce -> registry -> put raw
                  fixtures -> convert to client format -> put ready response

GET /availability/dataflow/{agency}/{id}/{version}/...
  AvailabilityQuery30Controller.getSdmxBeans -> translateStructureQuery("dataflow", ..., "descendants", "full", null, sourceUrn)
    AdapterRouterImpl.getSdmxBeans -> RawStructureFetcherImpl.fetch -> fixtures -> parse
```

Facts the design relies on:

- `QueryTranslator` and `AdapterRouter` have no request-scoped dependencies. A background job can
  call them with the same arguments as a controller and get identical keys.
- A client request is served from the ready-response cache before the raw cache is consulted
  (`AdapterRouterImpl.getStructures`, lines 189-200). A ready response under the live key answers
  the request without any registry call.
- There is no parsed-beans cache. `getSdmxBeans` applies the structure fixtures to the raw bytes
  and parses them on every call (`AdapterRouterImpl.java:287-308`).
- `InMemoryCacheService` uses plain `expireAfterWrite` Caffeine caches with no size bound and no
  refresh. `RedisCacheService` adds a random jitter to each TTL on write.
- OECD has no client-side rate limiter. Its `resilienceConfig` has no `rateLimit` block and
  `defaultRateLimitingEnabled` is `false`. Only `RateLimitRetryClient` retries a 429.
- OECD's native availability responses aren't cached anywhere, so availability can't be
  prewarmed. Caching them is out of scope (see "Out of scope").
- The application has no lifecycle hooks or Spring events today. `@EnableScheduling` is active
  only in `CONFIG_SERVER` mode (`ConfigServerSchedulingConfig`).

## Solution

The design has three parts:

1. **Preheat.** A background job runs the dataflow list from the root `preheat` configuration
   section through the same translator and router calls that a client request uses. The list is
   grouped by client media type, for example `application/vnd.sdmx.structure+xml;version=2.1`.
   The media type states what the client wants: the format and, through its `version`
   parameter, the SDMX version. The job runs at startup, after every configuration change, and
   periodically.
2. **Descendants splitting into ready responses.** For each listed dataflow, preheat fetches the
   dataflow with `references=descendants`. It takes every concept scheme and codelist from the
   fixture-applied, parsed result. It serializes each one as a single-artefact message in the
   group's media type. It stores that message in the ready-response cache under the key of the
   point request the client sends. The key is built by translating the point request with the
   group's media type and the DSD URN, exactly as a live StatGPT request is translated. No point
   request goes to the registry.
3. **Refresh-ahead and negative caching.** Each periodic run refreshes raw entries whose
   remaining TTL falls under a threshold, bypassing the cache read, and rebuilds split ready
   responses near expiry from the cached descendants. A local cache with a short TTL remembers
   empty registry answers.

The following diagram shows the preheat flow:

```
PreheatService (single "preheat" thread)
  for each group {mediaType, dataflows} in ProxyConfiguration.preheat
    for each "AGENCY:ID(VERSION)" in group.dataflows
      Q1 = translate("dataflow", AGENCY, ID, VERSION, "datastructure", "full", mediaType, null)
      skip unless Q1's structure endpoint has preheatEnabled
      ensureRaw(Q1)                     -- paced upstream call only on miss or near expiry
      router.getStructures(Q1)          -- fills the ready response for mediaType
      flow = router.getSdmxBeans(Q1).getDataflows()  -> resolved version, DSD URN
      Q2 = translate("dataflow", AGENCY, ID, flow.version, "descendants", "full", null, null)
      ensureRaw(Q2)                     -- paced upstream call only on miss or near expiry
      closure = router.getSdmxBeans(Q2) -- fixtures applied, parsed
      for each concept scheme and codelist A in closure
        Qp = translate(type(A), A.agency, A.id, A.version, null, "full", mediaType, dsdUrn)
        if ready response for Qp is missing or near expiry:
          put ready response(key(Qp), convert(single-artefact message of A, mediaType))
```

A full preheat of the 19 OECD dataflows costs 38 OECD calls (`Q1` and `Q2` per dataflow). At the
default pacing of one call every 4 seconds, that's about 2.5 minutes, against 9.5 minutes and
184 calls for today's cold load.

### Decisions agreed with the user

1. **Variant "replay plus descendants splitting".** Replay keeps the keys identical to live
   traffic. Splitting removes the 146 point calls. A bulk multi-ID request
   (`dataflow/{agency}/{id1+id2}/...`) is deferred, because per-dataflow descendants is already
   cheap enough.
2. **The preheat configuration states what the client wants.** Each group names one client media
   type. The media type carries the format and the SDMX version, so no separate `sdmxVersion`
   field exists. Preheat warms, and splits into, exactly that format.
3. **Splitting writes ready responses, not raw entries.** The artefacts come from the
   descendants beans after fixtures, so the split is safe for registries with raw-byte fixtures.
   Splitting is part of preheat and applies to every prewarmed dataflow. There is no separate
   per-registry split switch.
4. **Only opted-in registries are prewarmed.** `preheatEnabled` on
   `StructureEndpointConfiguration`, off by default, enabled for OECD.
5. **Editing the preheat list doesn't empty the caches.** Only changes outside `preheat` invalidate
   them (step 2).
6. **Availability is out of scope.** OECD native availability isn't cached, so there's nothing to
   warm.
7. **Empty answers are cached for a short TTL**, for all registries.
8. **Preheat is off by default** and enabled per deployment, so tests and E2E runs never call live
   registries.

### Why the split matches the live response

A live point request without a ready response runs `getStructuresConversion`. It fetches the
point raw bytes, applies the structure fixtures, parses them, and converts the beans to the
client media type with `StreamingStructureConversionService.convert`. The split takes the same
artefact from the descendants raw bytes after the same fixtures, and converts it with the same
`convert(SdmxBeans, OutputStream, MediaType)` method.

Two differences remain, and the verification plan covers them:

- **Fixtures run on a different document.** A fixture sees the descendants message instead of a
  single-artefact message. For OECD this is moot: its structure endpoint has no fixtures. For a
  registry with fixtures, a golden test against that registry's fixtures has to pass before its
  dataflows go into the preheat list.
- **Metadata-attribute-usage handling.** `getStructuresConversion` captures and reinjects
  `metadataAttributeUsages` (designs 027, 032, 036). These belong to DSDs and MSDs, which the
  split doesn't write. Concept schemes and codelists carry none.

The split passes the descendants message header into the single-artefact container, so the
sender matches the live conversion. `id` and `prepared` differ, as they do between any two
conversions.

The equivalence holds only under two conditions. Preheat checks both for each point query, and
when one fails it doesn't split that artefact. It logs a `WARN` and counts
`sdmxproxy.preheat.split_skipped` with the reason. The configuration then visibly asks for
something the split can't deliver, and live requests for that artefact take today's path.

- **No bypass for the group media type** (`reason=bypass`). When
  `FormatSupportChecker.canBypassStructureFormat(versionConfig, mediaType)` is true,
  `AdapterRouterImpl.getStructures` (lines 204-207) serves the registry's bytes verbatim through
  `getStructuresBypass`, without fixtures or re-serialization. A split response would differ.
  No bundled structure endpoint enables bypass.
- **Same endpoint as the descendants query** (`reason=endpoint`). `Q2` is translated with a null
  Accept, which `mapMediaType` turns into `application/json`, and it routes by the dataflow
  agency. A point query uses the group media type and routes by the DSD URN. On a registry with
  both a 2.1 and a 3.0 block, or when the DSD agency's primary registry differs, they can
  resolve to different endpoints, fixtures, and return formats. The split writes only when the
  point query's registry name, SDMX version, structure endpoint URL, and return format equal
  `Q2`'s. Every bundled registry has one SDMX version, so this holds for all of them today.

### Effect on traffic without preheat

Splitting runs only inside preheat, for the configured media types. A client that requests a
dataflow that isn't in the list, or requests another format, gets today's behavior: one
registry call per point artefact. The negative cache (P5) applies to all traffic.

## Configuration

### Proxy configuration (`sdmx_registries_config.json` and config server)

The design adds a root field and one structure endpoint flag. The following example shows them
in the bundled configuration:

```json
{
  "configs": [
    {
      "name": "OECD",
      "versions": {
        "SDMX_2_1": {
          "structureEndpointConfig": {
            "url": "https://sdmx.oecd.org/public/rest/",
            "preheatEnabled": true
          }
        }
      }
    }
  ],
  "agencies": [],
  "preheat": [
    {
      "mediaType": "application/vnd.sdmx.structure+xml;version=2.1",
      "dataflows": [
        "OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)",
        "OECD.SDD.STES:DSD_STES@DF_BTS(latest)"
      ]
    }
  ]
}
```

The dataflow reference format `AGENCY:ID(VERSION)` is the one StatGPT admin displays in its
dataset list. The version is passed to the translator verbatim, so `latest` produces the same key
as StatGPT's `latest` request. To warm a second format for the same dataflows, add a second
group with the other media type.

The phase 1 list is the 19 OECD datasets from the cold-start log:

```
OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)
OECD.SDD.STES:DSD_STES@DF_BTS(latest)
OECD.SDD.STES:DSD_STES@DF_CS(latest)
OECD.WISE.WDP:DSD_HSL@DF_HSL_CWB(latest)
OECD.ECO.MAD:DSD_EO@DF_EO(latest)
OECD.CFE.EDS:DSD_LA_EXTREME_TEMP_DDOWN@DF_EXTREME_TEMP_DDOWN(latest)
OECD.DAF.INV:DSD_FDI@DF_FDI_INC_IND(latest)
OECD.EDU.IMEP:DSD_EAG_UOE_FIN@DF_UOE_FIN_INDIC_SOURCE_NATURE(latest)
OECD.GOV.GIP:DSD_GOV_TDG_SPS_GPC@DF_GOV_GPC_2023(latest)
OECD.SDD.TPS:DSD_SDBSBD_ISIC4@DF_BD_API(latest)
OECD.ECO.MAD:DSD_EO_LTB@DF_EO_LTB(latest)
OECD.STI.PIE:DSD_PATENTS@DF_PATENTS(latest)
OECD.CFE.EDS:DSD_LA_DEMO_POP_AGE_DDOWN@DF_POP_AGE_DDOWN(latest)
OECD.TAD.TPD:DSD_STRI@DF_STRI_MAIN(latest)
OECD.EDU.IMEP:DSD_EAG_UOE_NON_FIN_STUD@DF_UOE_NF_STUD_DB(latest)
OECD.EDU.ECS:DSD_TALIS@DF_TALIS(latest)
OECD.STI.PIE:DSD_TIM_2025@DF_TIM_2025(latest)
OECD.STI.DEP:DSD_TQ_SURVEY@DF_TRUTH_QUEST(latest)
OECD.SDD.TPS:DSD_BIMTS@DF_BIMTS_HS2017_2D(latest)
```

The deployed configuration lives in the config server, not in the bundled file. The same section
has to be added there for the deployment to prewarm.

### Application properties (`application.yaml`)

Operational settings go into Spring properties, not the proxy configuration, because they tune
the proxy process and don't describe registries:

```yaml
sdmxproxy:
  preheat:
    enabled: ${SDMXPROXY_PREHEAT_ENABLED:false}
    startDelay: PT10S
    configChangeDebounce: PT10S
    upstreamInterval: PT4S
    refreshInterval: PT1H
    refreshAhead: PT12H
    refreshSpread: PT6H
    readyRefreshAhead: PT2H
    maxRefreshesPerRun: 10
    rateLimitBackoff: PT30S
    maxAttemptsPerDataflow: 3
  cache:
    singleFlight:
      emptyResultTtl: PT10M
```

The refresh settings keep preheated entries from expiring together:

- **Per-key threshold.** A raw entry is refreshed when its remaining TTL falls under
  `refreshAhead + offset(key)`, where `offset(key)` is `Math.floorMod(key.hashCode(), refreshSpread.toMinutes())`
  minutes. Entries written in the same startup run reach their thresholds in different hourly
  runs, and each refresh keeps its key's offset, so they stay spread apart. With the defaults a
  raw entry is refreshed after 6 to 12 hours of its 24-hour life.
- **Refresh budget.** One run makes at most `maxRefreshesPerRun` refresh calls. Entries over the
  budget wait for the next run, which still leaves several runs before they expire. The budget
  doesn't apply to missing entries: a cold fill after a restart or `invalidateAll()` fetches
  everything in one run.
- **Many attempts before expiry.** With `refreshAhead` of 12 hours, an entry gets at least 12
  hourly attempts before it expires. A registry outage shorter than that doesn't let preheated
  entries expire.

Constraints on the values:

- `refreshAhead + refreshSpread` must stay under the raw TTL (`ttl.parsedStructures`, PT24H).
  Otherwise every run refreshes every entry.
- `refreshAhead` must exceed `refreshInterval` plus the raw entries' Redis jitter
  (`ttl.parsedStructures.jitter`, PT1H).
- `readyRefreshAhead` applies to split ready responses, which live 6 hours. It must exceed
  `refreshInterval` plus the ready-response Redis jitter (`ttl.readyResponses.jitter`, PT30M),
  and stay well under 6 hours. With PT2H, a split ready response is rebuilt about every 4 to 5
  hours, from cached descendants and without registry calls.

`PreheatProperties` validates the three constraints at startup and fails the context with a
clear message when one is broken.

Preheat is off by default. The bundled configuration carries the OECD list, and nine
`@SpringBootTest` classes in `sdmx-proxy/src/test/java` load it with the main `application.yaml`
(there is no test `application.yaml`). With preheat on by default, every build and every E2E
`bootRun` would call live OECD. The deployment enables it with `SDMXPROXY_PREHEAT_ENABLED=true`.

## Implementation plan

### Step 1: Configuration model

**New file:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/PreheatConfiguration.java`

```java
package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.List;

/**
 * One preheat group: the dataflows to keep warm for one client media type. See design 044.
 */
@Data
public class PreheatConfiguration {

    /**
     * Client {@code Accept} value to warm and split into, for example
     * {@code application/vnd.sdmx.structure+xml;version=2.1}. Its {@code version} parameter
     * selects the SDMX version exactly as a client request's Accept does.
     */
    private String mediaType;

    /**
     * Dataflow references in the form {@code AGENCY:ID(VERSION)}, for example
     * {@code OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)}.
     */
    private List<String> dataflows;
}
```

**Modified:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ProxyConfiguration.java`.
Add after `structureFanOutEnabled`:

```java
    /**
     * Dataflows the proxy keeps warm in the structure caches, grouped by client media type.
     * Only registries whose structure endpoint sets {@code preheatEnabled} are warmed. Null or
     * empty disables preheat. See design 044.
     */
    private List<PreheatConfiguration> preheat;
```

**Modified:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureEndpointConfiguration.java`.
Add one field:

```java
    /**
     * When true, dataflows of this registry listed in the root {@code preheat} section are kept
     * warm, and their concept schemes and codelists are split into ready responses. Default
     * false. See design 044.
     */
    private boolean preheatEnabled;
```

**Modified:** `sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigValidator.java`.
Add `validatePreheat(configuration.getPreheat())` at the end of `validate`. It rejects:

- a null group;
- a group with a null or blank `mediaType`, or one that `MediaType.parseMediaType` can't parse;
- a group whose media type isn't a structure media type (subtype doesn't contain `structure`)
  or has no `version` parameter;
- a group with a null or empty `dataflows` list;
- a dataflow reference that doesn't match `^[^:()]+:[^:()]+\([^:()]+\)$`.

It doesn't check that the agency routes to a registry. Routing changes at runtime, and preheat
skips unroutable entries with a warning. `POST /config` and the classpath source skip this
validator, so `PreheatService` treats null groups and null lists as empty and logs malformed
references (step 7).

### Step 2: Configuration-changed event and the invalidation rule

The proxy has no event for configuration changes. Preheat and the fetcher's local caches need
one.

`ProxyConfiguration` is a Lombok `@Data` class, so the `preheat` field takes part in `equals`.
Both change paths call `invalidateAll()` whenever `!Objects.equals(previous, config)`. Without a
rule, adding one dataflow to the list would empty every cache of every registry, and phase 2
would do that on every dataset change. **A change that touches only `preheat` must not
invalidate the caches.** It only publishes the event, so preheat picks up the new list.

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationChangedEvent.java`

```java
package com.epam.sdmxproxy.registry.configuration;

/**
 * Published after the effective proxy configuration changed.
 *
 * @param source            where the change came from, for logs
 * @param cachesInvalidated whether the change touched anything besides {@code preheat} and the
 *                          caches were therefore invalidated
 */
public record ProxyConfigurationChangedEvent(String source, boolean cachesInvalidated) {
}
```

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationComparator.java`

```java
package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

import java.util.Objects;

/**
 * Compares two proxy configurations for cache relevance. {@code preheat} only selects what to
 * warm and never makes a cached entry wrong, so it is ignored. See design 044.
 */
public final class ProxyConfigurationComparator {

    private ProxyConfigurationComparator() {
    }

    public static boolean sameForCaching(ProxyConfiguration left, ProxyConfiguration right) {
        return Objects.equals(withoutPreheat(left), withoutPreheat(right));
    }

    private static ProxyConfiguration withoutPreheat(ProxyConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        ProxyConfiguration copy = new ProxyConfiguration();
        copy.setConfigs(configuration.getConfigs());
        copy.setAgencies(configuration.getAgencies());
        copy.setStructureFanOutEnabled(configuration.isStructureFanOutEnabled());
        return copy;
    }
}
```

The copy must list every `ProxyConfiguration` field except `preheat`. A unit test
(`ProxyConfigurationComparatorTest.shouldCoverEveryFieldExceptPreheat`) uses reflection over the
declared fields to fail when someone adds a field and forgets the comparator.

**Modified:** `ProxyConfigurationProviderImpl` and `ConfigServerConfigExtractor` (both under
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/`). Add a
`private final ApplicationEventPublisher eventPublisher;` field (both use
`@RequiredArgsConstructor`). Replace each change block with the following, using
`"config-server"` as the source in the extractor:

```java
        if (!Objects.equals(previous, configuration)) {
            boolean invalidate = !ProxyConfigurationComparator.sameForCaching(previous, configuration);
            if (invalidate) {
                log.info("Registry configuration changed; invalidating cache");
                cacheService.getObject().invalidateAll();
            } else {
                log.info("Preheat configuration changed; caches kept");
            }
            eventPublisher.publishEvent(new ProxyConfigurationChangedEvent("runtime-override", invalidate));
        }
```

`ProxyConfigurationProviderImplTest:34` constructs the provider directly and needs the new
constructor argument.

### Step 3: Remaining TTL in `CacheService`

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheService.java`

```java
    /**
     * Remaining time to live of a raw-structures entry, or empty when the entry is absent.
     */
    Optional<Duration> getRawStructuresTimeToLive(String key);

    /**
     * Remaining time to live of a ready-response entry, or empty when the entry is absent.
     */
    Optional<Duration> getReadyResponseTimeToLive(String key);
```

**Modified:** `InMemoryCacheService`. Caffeine 3 exposes the entry age through the fixed
expiration policy. The same helper serves both caches:

```java
    @Override
    public Optional<Duration> getRawStructuresTimeToLive(String key) {
        return timeToLive(rawStructuresCache, key);
    }

    @Override
    public Optional<Duration> getReadyResponseTimeToLive(String key) {
        return timeToLive(readyResponseCache, key);
    }

    private static Optional<Duration> timeToLive(Cache<String, byte[]> cache, String key) {
        return cache.policy().expireAfterWrite().flatMap(expiration -> expiration.ageOf(key).map(age -> expiration.getExpiresAfter().minus(age)));
    }
```

**Modified:** `RedisCacheService`. `RedisTemplate.getExpire` returns `-2` for a missing key and
`-1` for a key without TTL. The ready-response method uses `readyResponseRedisTemplate` and
`READY_RESPONSE_PREFIX` the same way:

```java
    @Override
    public Optional<Duration> getRawStructuresTimeToLive(String key) {
        return timeToLive(rawStructuresRedisTemplate, RAW_STRUCTURES_PREFIX + key);
    }

    private static Optional<Duration> timeToLive(RedisTemplate<String, byte[]> template, String redisKey) {
        try {
            Long millis = template.getExpire(redisKey, TimeUnit.MILLISECONDS);
            if (millis == null || millis == -2) {
                return Optional.empty();
            }
            return Optional.of(millis == -1 ? Duration.ofDays(3650) : Duration.ofMillis(millis));
        } catch (Exception e) {
            log.error("Error reading TTL from Redis cache: {}", redisKey, e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }
```

A TTL lookup, rather than a value read, is also the existence check preheat uses. In REDIS mode a
value read would download the whole entry.

### Step 4: Descendants splitter

The splitter writes one ready response per artefact. Preheat calls it with point queries that it
translated itself (step 7), so the splitter doesn't build keys from guesses.

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DescendantsSplitter.java`

```java
package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;

/**
 * Stores one concept scheme or codelist from a descendants closure as the ready response to its
 * own point request. See design 044.
 */
public interface DescendantsSplitter {

    /**
     * Ready-response key of {@code pointQuery}, built exactly as {@code AdapterRouterImpl.getStructures} builds it.
     */
    String readyResponseKey(TranslatedStructureQuery pointQuery);

    /**
     * Serializes {@code artefact} as a single-artefact message in the point query's media type,
     * with {@code header} as the message header, and stores it under {@link #readyResponseKey}.
     */
    void store(TranslatedStructureQuery pointQuery, MaintainableBean artefact, HeaderBean header);
}
```

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DescendantsSplitterImpl.java`

```java
package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DescendantsSplitterImpl implements DescendantsSplitter {

    private final StreamingStructureConversionService conversionService;
    private final CacheService cacheService;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;

    @Override
    public String readyResponseKey(TranslatedStructureQuery pointQuery) {
        return CacheKeyGenerator.generateResponseKey(pointQuery, pointQuery.getContentType(), Collections.emptyMap(), sdmx21QueryNormalizer);
    }

    @Override
    public void store(TranslatedStructureQuery pointQuery, MaintainableBean artefact, HeaderBean header) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.convert(new SdmxBeansImpl(header, List.of(artefact)), out, pointQuery.getContentType());
        cacheService.putReadyResponse(readyResponseKey(pointQuery), out.toByteArray());
    }
}
```

**Key points:**

- `readyResponseKey` repeats the call in `AdapterRouterImpl.getStructures`:
  `CacheKeyGenerator.generateResponseKey(query, requestedMediaType, Collections.emptyMap(), sdmx21QueryNormalizer)`,
  with `requestedMediaType = query.getContentType()`.
- `convert(SdmxBeans, OutputStream, MediaType)` is the method `getStructuresConversion` reaches
  through `convert(InputStream, OutputStream, SdmxFormat, MediaType)`
  (`StreamingStructureConversionService.java:56-80`).
- `new SdmxBeansImpl(null, List.of(artefact))`
  (`sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/container/SdmxBeansImpl.java:60`)
  creates a container without a header. Fan-out already writes header-less merged beans through
  the same `convert` method.
- The splitter has no `@EventListener` and no state. It depends on the conversion service, the
  cache, and the normalizer, none of which depends on preheat.

### Step 5: Fetcher changes

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcher.java`

```java
    /**
     * Fetches from the registry even when the raw cache holds the entry, and replaces the entry.
     * Coalesced with concurrent fetches of the same key.
     */
    byte[] refresh(TranslatedStructureQuery query);
```

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImpl.java`

1. Add a local negative cache next to `coolingDown`:
   `Caffeine.newBuilder().expireAfterWrite(cacheProperties.getSingleFlight().getEmptyResultTtl()).ticker(ticker).build()`.
2. In `fetch`, after the raw cache miss and before the cooldown check, return `new byte[0]` when
   the key is in the negative cache.
3. Split `load` into a cache re-check and a `loadFromRegistry(query, key)` method. `refresh` calls
   `throwIfCoolingDown(key)` and then
   `requestCoalescer.coalesce(key, () -> loadFromRegistry(query, key))`, skipping the cache and
   negative-cache reads. Keeping the cooldown check stops preheat from spending OECD quota on a
   key that just ended in 429, and surfaces `RegistryCoolingDownException` to `warmWithRetry`.
4. In `loadFromRegistry`, put the key into the negative cache when the body is null or empty, and
   don't call `putRawStructures` for empty bytes. Today a non-null stream with zero bytes is
   stored in the raw cache for 24 hours (`RawStructureFetcherImpl.java:98-99`). The negative
   cache replaces that with the short `emptyResultTtl`.
5. Add `@EventListener(ProxyConfigurationChangedEvent.class)` method `onConfigurationChanged()` that
   calls `emptyResults.invalidateAll()`.

**Modified:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/SingleFlightProperties.java`.
Add:

```java
    /**
     * How long an empty registry answer (for example HTTP 204) is served without calling the
     * registry again. Local to each instance. See design 044.
     */
    private Duration emptyResultTtl = Duration.ofMinutes(10);
```

`refresh` uses the same key as `fetch`. If a live `fetch` flight for that key is already running,
`InProcessRequestCoalescer.coalesce` (`putIfAbsent`) makes the refresh join it, and the entry
gets the live flight's bytes and TTL. That entry is as fresh as a refresh would make it, so
nothing is lost.

### Step 6: Preheat properties

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/PreheatProperties.java`

```java
package com.epam.sdmxproxy.services.preheat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "sdmxproxy.preheat")
public class PreheatProperties {

    /**
     * Off by default, so that {@code @SpringBootTest} contexts and the E2E {@code bootRun}, which
     * load the bundled configuration, never call live registries. Deployments set
     * {@code SDMXPROXY_PREHEAT_ENABLED=true}.
     */
    private boolean enabled;
    private Duration startDelay = Duration.ofSeconds(10);
    private Duration configChangeDebounce = Duration.ofSeconds(10);
    /** Minimum time between two upstream calls made by preheat. */
    private Duration upstreamInterval = Duration.ofSeconds(4);
    private Duration refreshInterval = Duration.ofHours(1);
    /** Base threshold: raw entries with less remaining TTL than this plus their offset are refreshed. */
    private Duration refreshAhead = Duration.ofHours(12);
    /** Width of the per-key offset added to {@code refreshAhead}, so refreshes don't align. */
    private Duration refreshSpread = Duration.ofHours(6);
    /** Split ready responses with less remaining TTL than this are rebuilt from cached descendants. */
    private Duration readyRefreshAhead = Duration.ofHours(2);
    /** Upper bound on refresh calls per run. Missing entries are fetched regardless. */
    private int maxRefreshesPerRun = 10;
    /** Wait after a 429 or cooldown before retrying the same dataflow. */
    private Duration rateLimitBackoff = Duration.ofSeconds(30);
    private int maxAttemptsPerDataflow = 3;
}
```

The `@Component` plus `@ConfigurationProperties` pattern matches `CacheProperties`.

### Step 7: Preheat service

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DataflowReference.java`.
A record `DataflowReference(String agency, String id, String version)` with a static
`parse(String)` that applies the pattern from step 1 and throws `IllegalArgumentException` on a
mismatch.

**New file:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/PreheatService.java`

The service owns one `ScheduledExecutorService` with a single named platform thread (`preheat`),
so runs never overlap. It reacts to three triggers:

| Trigger | Action |
|---|---|
| `@EventListener(ApplicationReadyEvent.class)` | Schedule a run after `startDelay`, then a fixed-delay run every `refreshInterval`. |
| `@EventListener(ProxyConfigurationChangedEvent.class)` | Increment `generation`, cancel the pending one-shot run, and schedule a new run after `configChangeDebounce`. This fires for preheat-only changes too, which keep the caches (step 2). |
| Periodic | Run the same plan. Cache hits cost nothing, so the periodic run only refreshes entries near expiry and rebuilds expired ready responses. |

The configuration-changed event arrives on an HTTP or config-poll thread. The listener increments
`generation` and replaces the pending one-shot `ScheduledFuture` on the calling thread, under a
lock on a private monitor. It doesn't submit the rescheduling to the preheat executor, where it
would queue behind the running run. `generation` is an `AtomicLong`.

A `scheduleWithFixedDelay` task stops for good after its first exception. `run()` therefore
catches every `RuntimeException`, logs it, and counts it, so one failed run never ends the
periodic refresh.

The run algorithm follows, as Java-like pseudocode:

```java
void run() {
    try {
        if (!properties.isEnabled()) {
            return;
        }
        long runGeneration = generation.get();
        RunState run = new RunState();                         // refresh budget and counters of this run
        for (PreheatConfiguration group : nullToEmpty(configurationProvider.getConfiguration().getPreheat())) {
            if (group == null || group.getMediaType() == null || group.getMediaType().isBlank()) {
                log.warn("Preheat group without a media type skipped");
                continue;
            }
            for (String reference : nullToEmpty(group.getDataflows())) {
                checkActive(runGeneration);
                warmWithRetry(group.getMediaType(), reference, runGeneration, run);   // catches per-dataflow failures
            }
        }
    } catch (PreheatCancelledException e) {
        log.info("Preheat run stopped: {}", e.getMessage());
    } catch (RuntimeException e) {
        log.error("Preheat run failed", e);
        runFailureCounter.add(1);
    }
}

void warmDataflow(String mediaType, DataflowReference ref, long runGeneration, RunState run) {
    TranslatedStructureQuery flowQuery = queryTranslator.translateStructureQuery("dataflow", ref.agency(), ref.id(), ref.version(), "datastructure", "full", mediaType, null);
    if (!flowQuery.getVersionConfiguration().getStructureEndpointConfig().isPreheatEnabled()) {
        return;                                                // result "skipped"
    }
    ensureRaw(flowQuery, runGeneration, run);
    checkActive(runGeneration);
    warmReadyResponse(flowQuery);                              // a hit is a no-op; see key points
    DataflowBean flow = single(adapterRouter.getSdmxBeans(flowQuery).getDataflows());
    String dsdUrn = flow.getDataStructureRef().getTargetUrn();
    // Accept and source URN null, as in the availability controller's descendants call.
    TranslatedStructureQuery descendantsQuery = queryTranslator.translateStructureQuery("dataflow", ref.agency(), ref.id(), flow.getVersion().toString(), "descendants", "full", null, null);
    ensureRaw(descendantsQuery, runGeneration, run);
    SdmxBeans closure = adapterRouter.getSdmxBeans(descendantsQuery);    // fixtures applied
    if (closure.getConceptSchemes().isEmpty() && closure.getCodelists().isEmpty()) {
        throw new PreheatDataflowException("descendants answer is empty for " + ref);   // result "failed"
    }
    for (MaintainableBean scheme : closure.getConceptSchemes()) {
        split("conceptscheme", scheme, closure.getHeader(), mediaType, dsdUrn, descendantsQuery, runGeneration);
    }
    for (MaintainableBean codelist : closure.getCodelists()) {
        split("codelist", codelist, closure.getHeader(), mediaType, dsdUrn, descendantsQuery, runGeneration);
    }
}

void split(String type, MaintainableBean artefact, HeaderBean header, String mediaType, String dsdUrn, TranslatedStructureQuery descendantsQuery, long runGeneration) {
    checkActive(runGeneration);
    TranslatedStructureQuery pointQuery = queryTranslator.translateStructureQuery(type, artefact.getAgencyId(), artefact.getId(), artefact.getVersion().toString(), null, "full", mediaType, dsdUrn);
    if (FormatSupportChecker.canBypassStructureFormat(pointQuery.getVersionConfiguration(), pointQuery.getContentType())) {
        skipSplit(pointQuery, "bypass");                       // WARN + split_skipped{reason}
        return;
    }
    if (!sameEndpoint(pointQuery, descendantsQuery)) {
        skipSplit(pointQuery, "endpoint");
        return;
    }
    if (readyResponseNeedsBuild(pointQuery)) {
        descendantsSplitter.store(pointQuery, artefact, header);
        splitEntriesCounter.add(1, Attributes.of(REGISTRY, pointQuery.getRegistryConfiguration().getName()));
    }
}

/** Same registry name, SDMX version, structure endpoint URL, and registry return format. */
boolean sameEndpoint(TranslatedStructureQuery a, TranslatedStructureQuery b) {
    return a.getRegistryConfiguration().getName().equals(b.getRegistryConfiguration().getName())
            && a.getVersionConfiguration().getSdmxVersion() == b.getVersionConfiguration().getSdmxVersion()
            && Objects.equals(a.getVersionConfiguration().getStructureEndpointConfig().getUrl(), b.getVersionConfiguration().getStructureEndpointConfig().getUrl())
            && a.getRegistryReturnFormat() == b.getRegistryReturnFormat();
}

boolean readyResponseNeedsBuild(TranslatedStructureQuery query) {
    Optional<Duration> ttl = cacheService.getReadyResponseTimeToLive(descendantsSplitter.readyResponseKey(query));
    return ttl.isEmpty() || ttl.get().compareTo(properties.getReadyRefreshAhead()) < 0;
}

/** Calls the registry, paced, only when the raw entry is missing, or near expiry and within the run's budget. */
void ensureRaw(TranslatedStructureQuery query, long runGeneration, RunState run) {
    String key = CacheKeyGenerator.generateStructureKey(query, sdmx21QueryNormalizer);
    Optional<Duration> ttl = cacheService.getRawStructuresTimeToLive(key);
    if (ttl.isPresent() && ttl.get().compareTo(refreshThreshold(key)) >= 0) {
        return;
    }
    if (ttl.isPresent() && run.refreshes() >= properties.getMaxRefreshesPerRun()) {
        run.deferRefresh();                                  // next run picks it up
        return;
    }
    checkActive(runGeneration);
    pace();
    if (ttl.isEmpty()) {
        rawStructureFetcher.fetch(query);
    } else {
        rawStructureFetcher.refresh(query);
        run.countRefresh();
    }
}

/** refreshAhead plus a stable per-key offset in [0, refreshSpread). */
Duration refreshThreshold(String key) {
    long spreadMinutes = Math.max(1, properties.getRefreshSpread().toMinutes());
    return properties.getRefreshAhead().plusMinutes(Math.floorMod(key.hashCode(), spreadMinutes));
}

void warmReadyResponse(TranslatedStructureQuery query) {
    adapterRouter.getStructures(query).writeTo(OutputStream.nullOutputStream());   // @SneakyThrows
}

/** Throws PreheatCancelledException when the configuration changed or the thread was interrupted. */
void checkActive(long runGeneration) {
    if (Thread.currentThread().isInterrupted()) {
        throw new PreheatCancelledException("preheat thread interrupted");
    }
    if (generation.get() != runGeneration) {
        throw new PreheatCancelledException("superseded by a configuration change");
    }
}
```

**Key points:**

- The point query is translated from the artefact's own agency, ID, and version with the group's
  media type and the DSD URN as source artefact, the way StatGPT's request is. The translator
  then picks the same registry, SDMX version, and return format as for the live request, so the
  ready-response key matches (verified by `PreheatKeyEqualityTest`).
- The split never calls the registry: the closure comes from the cached descendants entry. The
  only paced calls are the two `ensureRaw` calls per dataflow.
- The `Q1` ready response and the split ready responses are both written in the group's media
  type. A second group with another media type reuses the raw entries and makes no registry
  calls.
- The `Q1` ready response isn't refreshed ahead of expiry: `getStructures` returns a hit as is,
  and the cache has no remove operation. After its 6-hour TTL it stays missing until the next
  periodic run, at most `refreshInterval` later. A live request in that window converts from the
  warm raw entry and makes no registry call, so the gap costs only one local conversion.
- A descendants answer with no concept schemes and no codelists (a 204, or the negative cache)
  makes the dataflow count as `failed`. `PreheatDataflowException` is a private runtime exception
  of the preheat package, caught by `warmWithRetry`.
- `skipSplit` logs one `WARN` per artefact and reason and counts
  `sdmxproxy.preheat.split_skipped{registry, reason}`. See "Why the split matches the live
  response" for the two reasons.
- `RunState` is a small mutable holder created per run: refresh calls made, refreshes deferred
  over the budget, and the counters for the summary line. Only the preheat thread touches it.
  Deferred refreshes are counted as `sdmxproxy.preheat.refreshes_deferred`.
- `checkActive` runs before every registry call and every split, not only between dataflows. A
  configuration change or `shutdownNow()` stops the run within one step instead of one dataflow.
  `PreheatCancelledException` is a private runtime exception of the preheat package.
- `pace()` sleeps until `upstreamInterval` has passed since the previous upstream call made by
  preheat. It calls `checkActive` after waking. It doesn't account for live traffic. Live traffic
  spends the same OECD budget, and single-flight merges any overlap.
- `warmWithRetry` parses the reference with `DataflowReference.parse`, then calls `warmDataflow`.
  It catches `TooManyRequestsException` (the abstract parent of `RegistryCoolingDownException`
  and `RateLimitExceededException`) and `FeignException` with status 429, waits
  `rateLimitBackoff`, and retries the dataflow up to `maxAttemptsPerDataflow` times. It rethrows
  `PreheatCancelledException`. When the thread is interrupted, it rethrows as
  `PreheatCancelledException` (the coalescer turns an interrupt into `UnexpectedStateException`).
  It logs and counts any other exception and moves to the next dataflow.
- An unroutable agency (`AgencyRoutingException`), a malformed reference, or an unsupported media
  type is logged at `WARN` and skipped.
- A dataflow that answers with an empty body yields no `DataflowBean`. `single(...)` throws, and
  the dataflow is counted as failed.
- `@PreDestroy` shuts the executor down with `shutdownNow()`.

**Metrics** follow the `sdmxproxy.<area>.<snake_case>` convention, with meter scope
`sdmx-proxy.preheat`:

- `sdmxproxy.preheat.runs` (counter, attribute `trigger`)
- `sdmxproxy.preheat.run_failures` (counter)
- `sdmxproxy.preheat.dataflows` (counter, attributes `registry` and `result` = `warmed`,
  `skipped`, `failed`)
- `sdmxproxy.preheat.upstream_calls` (counter, attribute `registry`)
- `sdmxproxy.preheat.refreshes_deferred` (counter, attribute `registry`)
- `sdmxproxy.preheat.split_entries` (counter, attribute `registry`)
- `sdmxproxy.preheat.split_skipped` (counter, attributes `registry` and `reason` = `bypass`,
  `endpoint`)

Each run ends with one `INFO` line: trigger, duration, dataflows warmed, skipped, and failed,
split entries written, and upstream calls made.

### Step 8: Bundled configuration and documentation

**Modified:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`. In the OECD
`SDMX_2_1.structureEndpointConfig`, add `"preheatEnabled": true`. Add the root `preheat` section
with one group, media type `application/vnd.sdmx.structure+xml;version=2.1`, and the 19
dataflows listed in "Configuration".

**Modified:** `sdmx-proxy/src/main/resources/application.yaml`. Add the `sdmxproxy.preheat` block
and `sdmxproxy.cache.singleFlight.emptyResultTtl` from "Application properties".

**Modified:** `sdmx-proxy-config/README.md`. Add a `preheat` row to the root
`ProxyConfiguration` table, a `preheatEnabled` row to the `StructureEndpointConfiguration`
table, and a new `### \`PreheatConfiguration\`` section with the five-column table (`Field`,
`Required`, `Description`, `Available Values`, `Default`).

## Out of scope

- **Native availability caching.** OECD availability responses aren't cached, and StatGPT sends
  about 19 to 28 of them per admin load. Caching them is a new cache domain with its own
  freshness questions and gets its own design.
- **Data requests.** Data isn't cached (design 014).
- **Splitting for traffic without preheat.** Natural descendants fetches don't split. A live
  descendants fetch has no client media type to split into, and StatGPT requests point
  artefacts before availability anyway.
- **Bulk multi-ID requests.** `dataflow/{agency}/{id1+id2}/latest?references=descendants` works on
  OECD (verified), but it would also need splitting dataflows and DSDs and resolving versions per
  dataflow. Per-dataflow descendants already costs only 38 calls.
- **Phase 2.** StatGPT admin writing the `preheat` section.
- **Serving stale entries on registry errors (stale-on-error).** If a registry stays down longer
  than `refreshAhead` (12 hours), preheated entries expire and live traffic falls back to
  today's behavior. Keeping entries past their TTL needs a change to both cache implementations
  and gets its own design.

## No changes required

These files need **no modification**:

- **`SdmxStructure30Controller.java`**, **`AvailabilityQuery30Controller.java`**: preheat calls the
  translator and router directly.
- **`QueryTranslatorImpl.java`**: preheat passes the same arguments a controller passes.
- **`CacheKeyGenerator.java`**: preheat and the splitter reuse `generateStructureKey` and
  `generateResponseKey` as is.
- **`AdapterRouterImpl.java`**: `getStructures` reads the ready response before anything else, so
  split entries are served without change. `getSdmxBeans` already applies fixtures.
- **`InProcessRequestCoalescer.java`**: `refresh` reuses `coalesce`.
- **`GenericRegistryAdapterImpl.java`**, Feign clients, and resilience configuration.
- **E2E framework** (`BaseRegistryTestSuite.java` and its configuration classes): preheat is off in
  E2E runs, and nothing outside preheat changes behavior visible to E2E except the negative cache.

## Files affected

| File | Change type | Description |
|------|-------------|-------------|
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/PreheatConfiguration.java` | New | Preheat group model |
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ProxyConfiguration.java` | Modified | `preheat` root field |
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/StructureEndpointConfiguration.java` | Modified | `preheatEnabled` |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` | Modified | OECD flag and preheat list |
| `sdmx-proxy-config/README.md` | Modified | Schema tables |
| `sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigValidator.java` | Modified | Validate `preheat` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationChangedEvent.java` | New | Configuration-changed event |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationComparator.java` | New | Cache-relevant comparison that ignores `preheat` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationProviderImpl.java` | Modified | Invalidation rule, publish the event |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/extractor/ConfigServerConfigExtractor.java` | Modified | Invalidation rule, publish the event |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheService.java` | Modified | `getRawStructuresTimeToLive`, `getReadyResponseTimeToLive` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/InMemoryCacheService.java` | Modified | TTL from Caffeine policy |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/RedisCacheService.java` | Modified | TTL from `PTTL` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/SingleFlightProperties.java` | Modified | `emptyResultTtl` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcher.java` | Modified | `refresh` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImpl.java` | Modified | Refresh, negative cache |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DescendantsSplitter.java` | New | Splitter interface |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DescendantsSplitterImpl.java` | New | Writes split ready responses |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/PreheatProperties.java` | New | Properties |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/DataflowReference.java` | New | Reference parser |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/preheat/PreheatService.java` | New | Scheduler and run |
| `sdmx-proxy/src/main/resources/application.yaml` | Modified | Preheat properties (off by default), `emptyResultTtl` |

Existing tests that mock `CacheService` or construct `RawStructureFetcherImpl`
(`RawStructureFetcherImplTest:252`) or `ProxyConfigurationProviderImpl`
(`ProxyConfigurationProviderImplTest:34`) need the new methods and constructor arguments.

## Edge cases

1. **StatGPT loads while preheat runs.** Both go through `RawStructureFetcher`, so single-flight
   merges identical raw keys. A StatGPT point request that arrives before preheat split the
   dataflow goes upstream as today. A ready response that StatGPT's own request writes first is
   kept until it nears expiry, because the split checks the TTL before writing.
2. **Configuration change during a run.** `checkActive` stops the run before the next registry
   call or split. A new run starts after the debounce. Entries fetched under the old
   configuration carry the old URL hash and are never read again (design 042).
3. **Config server starts with the classpath seed.** In `CONFIG_SERVER` mode the first poll
   usually changes the configuration, which triggers a new run. The startup run on the seed might
   warm a few entries that are invalidated a few seconds later. The `startDelay` and the
   debounce keep this small.
4. **`latest` resolves to a new version.** `Q1` (`latest`) is refreshed by TTL. When it returns a
   new version, preheat fetches descendants for the new version. Entries for the old version
   expire on their own.
5. **A codelist is shared by many dataflows.** The first dataflow that contains it writes the
   ready response. Later dataflows find a fresh TTL and skip it. All writers come from the same
   registry and carry the same content.
6. **Ready responses lost between runs.** `invalidateAll()` or an eviction removes split ready
   responses. The configuration-change event, or the next periodic run, rebuilds them from the
   raw descendants entry, which `invalidateAll()` also clears and preheat fetches again (2 calls
   per dataflow). Until then, StatGPT point requests go upstream as today.
7. **Registry answers 204 for a listed dataflow.** The negative cache remembers it for
   `emptyResultTtl`, the dataflow counts as failed, and the next periodic run tries again.
8. **Several replicas in LOCAL mode.** Each replica runs its own preheat: 38 OECD calls per
   replica at startup. With two replicas behind one egress IP, that's 76 calls, about four
   minutes at OECD's limit. This is acceptable for the deployment sizes known today.
9. **Several replicas in REDIS mode.** The first replica fills Redis. The others find hits and
   make no upstream calls. Near expiry, every replica can refresh the same raw entry once. This
   costs up to R calls per entry per refresh window. Split ready responses are rebuilt without
   registry calls.
10. **Memory.** A descendants response for one OECD dataflow is about 4&nbsp;MB. Raw
    descendants and split ready responses for 19 dataflows can take a few hundred megabytes of
    heap in LOCAL mode. Today's natural traffic already caches the descendants. The caches have
    no size bound. Check the deployment's heap limit against the measured size during manual
    verification. Each extra media type group adds one more set of ready responses.
11. **Old proxy or old config server, new configuration.** The proxy decodes config-server
    responses with feign's `new JacksonDecoder()`
    (`registry/configuration/configserver/ConfigServerFeignConfig.java:40`), which ignores unknown
    fields, so an old proxy ignores `preheat`. The config server deserializes with a plain
    `new ObjectMapper()` (`ConfigServerConfiguration.java:11`), which rejects unknown fields.
    Deploy the new config server before writing `preheat` into its stored configuration.
12. **Media type not supported by the registry path.** Translation fails for that group, and
    preheat logs one warning per dataflow and continues.
13. **Versions without a dot.** sdmx-core normalizes a version such as `1` to `1.0`
    (`sdmx-core-2.3.9/fusion-utils-sdmx/src/main/java/io/sdmx/utils/sdmx/xs/SdmxVersion.java:40-47`),
    while StatGPT requests the string it parsed itself (`dataflow_loader.py`). For such an
    artefact, preheat writes a ready response under the `1.0` key, and StatGPT's `1` request
    misses it and goes upstream. The same applies to `Q2` when a dataflow version has no dot.
    Preheat can't detect this, because its point queries and its split read the same normalized
    beans. Every OECD codelist, concept scheme, and dataflow version in the cold-start log has a
    dot. The manual verification checks the log for point calls after preheat, which is how a
    mismatch shows up.
14. **`FILESYSTEM` configuration source.** `FileSystemConfigExtractorProxy` rereads the file on
    every call and publishes no event. Preheat sees list edits only at its next periodic run.
15. **Two startup runs in `CONFIG_SERVER` mode.** The first scheduled poll can publish the change
    event before `ApplicationReadyEvent`. The single preheat thread runs the two runs one after
    the other, and the second one finds only cache hits.
16. **Fixtures on another registry.** If a registry with structure fixtures is added to the list,
    its split ready responses come from the descendants message after fixtures. A fixture that
    behaves differently on a single-artefact message would make the split differ from a live
    response. Run the golden test (`DescendantsSplitterImplTest`) against that registry's
    configuration before listing its dataflows.

## Verification

### Unit tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/preheat/DescendantsSplitterImplTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldStoreUnderRouterReadyResponseKey()` | `readyResponseKey` equals `CacheKeyGenerator.generateResponseKey(query, query.getContentType(), emptyMap(), normalizer)`, and `store` writes under it. |
| `codelistShouldMatchLiveConversion()` | Golden test: the split XML 2.1 ready response for `CL_VALUATION`, taken from an OECD descendants fixture, equals the output of `AdapterRouterImpl.getStructures` for a direct `CL_VALUATION` raw fixture, apart from the message header. |
| `conceptSchemeShouldMatchLiveConversion()` | The same for one concept scheme from the same descendants fixture, whose concepts carry representation references. |
| `shouldWriteJsonForJsonMediaType()` | A point query with `application/vnd.sdmx.structure+json;version=2.0.0` produces SDMX-JSON. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/preheat/PreheatKeyEqualityTest.java`

This test proves the central claim: the keys preheat writes equal the keys of StatGPT's live
requests. It builds both through a real `QueryTranslatorImpl` with an OECD-shaped configuration.

| Test method | Description |
|-------------|-------------|
| `codelistReadyKeyMatchesStatGptRequest()` | The ready-response key preheat writes for `SDMX:CL_FREQ(2.1)` equals the key of `translateStructureQuery("codelist", "SDMX", "CL_FREQ", "2.1", null, "full", "application/vnd.sdmx.structure+xml;version=2.1", dsdUrn)`, the controller's argument shape for StatGPT's request. |
| `conceptSchemeReadyKeyMatchesStatGptRequest()` | The same for a concept scheme. |
| `descendantsRawKeyMatchesAvailabilityController()` | Preheat's `Q2` raw key equals the key from the availability controller's argument shape (`"dataflow", agency, id, version, "descendants", "full", null, null`). |
| `flowReadyKeyMatchesStatGptRequest()` | Preheat's `Q1` ready-response key equals the live `latest?references=datastructure` key. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/preheat/PreheatServiceTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldWarmFlowDescendantsAndSplit()` | The run calls the translator with the exact StatGPT argument shapes, fetches `Q1` and `Q2`, and stores one split entry per concept scheme and codelist. |
| `shouldNotCallRegistryForPointArtefacts()` | No `RawStructureFetcher` call for any codelist or concept scheme. |
| `shouldSkipRegistryWithoutPreheatEnabled()` | No calls for a registry without the flag. |
| `shouldRefreshRawEntriesNearExpiry()` | A raw TTL under `refreshAhead` triggers `refresh`, not `fetch`. |
| `refreshThresholdShouldSpreadKeys()` | `refreshThreshold` is stable per key, lies in `[refreshAhead, refreshAhead + refreshSpread)`, and differs across a sample of OECD keys. |
| `shouldDeferRefreshesOverBudget()` | With 15 entries near expiry and `maxRefreshesPerRun=10`, one run refreshes 10 and defers 5. |
| `shouldFetchMissingEntriesBeyondBudget()` | Missing entries are fetched even after the refresh budget is spent. |
| `propertiesShouldRejectBrokenConstraints()` | `refreshAhead + refreshSpread` at or over the raw TTL, or `readyRefreshAhead` under `refreshInterval`, fails validation. |
| `shouldRebuildSplitEntriesNearExpiry()` | A ready-response TTL under `refreshAhead` triggers `store`; a fresh one doesn't. |
| `shouldStopWithinDataflowOnConfigurationChange()` | A generation change between two splits of one dataflow stops the run. |
| `shouldStopOnInterrupt()` | An interrupted preheat thread stops the run instead of moving to the next dataflow. |
| `shouldRetryAfterRateLimit()` | A `RegistryCoolingDownException` waits and retries up to the maximum. |
| `shouldContinueAfterFailedDataflow()` | One failing dataflow doesn't stop the others. |
| `shouldKeepPeriodicRunsAfterFailingRun()` | A run whose configuration lookup throws is logged, and the next scheduled run still executes. |
| `shouldTreatNullGroupsAndListsAsEmpty()` | A `preheat` list with a null group, a blank media type, or null `dataflows` doesn't throw and skips that group. |
| `shouldSkipSplitWhenMediaTypeIsBypassed()` | With bypass on for the group media type, no split entry is written and `split_skipped{reason=bypass}` is counted. |
| `shouldSkipSplitWhenPointQueryUsesAnotherEndpoint()` | A point query that resolves to another SDMX version or registry than `Q2` isn't split, and `split_skipped{reason=endpoint}` is counted. |
| `shouldFailDataflowWithEmptyDescendants()` | An empty closure counts the dataflow as `failed`. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/preheat/DataflowReferenceTest.java`:
parses `OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)` and rejects references with a missing version
or an extra colon.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationComparatorTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldIgnorePreheatChanges()` | Two configurations that differ only in `preheat` are the same for caching. |
| `shouldDetectRegistryChanges()` | A change in `configs` is detected. |
| `shouldCoverEveryFieldExceptPreheat()` | Reflection over `ProxyConfiguration` fields: every field except `preheat` is copied by the comparator. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationProviderImplTest.java`
(modified): a preheat-only change publishes the event with `cachesInvalidated=false` and doesn't
call `invalidateAll`.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/RawStructureFetcherImplTest.java`

| Test method | Description |
|-------------|-------------|
| `shouldServeEmptyResultFromNegativeCache()` | A second fetch after a 204 doesn't call the registry within `emptyResultTtl`. |
| `shouldNotStoreEmptyBytesInRawCache()` | A zero-byte body goes to the negative cache, not to `putRawStructures`. |
| `shouldRefetchEmptyResultAfterTtl()` | With the fake ticker advanced past the TTL, the registry is called again. |
| `shouldClearNegativeCacheOnConfigurationChange()` | `onConfigurationChanged` clears it. |
| `refreshShouldBypassCacheAndReplaceEntry()` | `refresh` calls the registry despite a hit and overwrites the entry. |
| `refreshShouldRespectCooldown()` | `refresh` throws `RegistryCoolingDownException` for a key in cooldown and doesn't call the registry. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/cache/InMemoryCacheServiceTest.java`
(new or existing): both TTL methods are empty for a missing key and decrease with time.

**File:** `sdmx-proxy-config-server/src/test/java/com/epam/sdmxproxy/configserver/service/ConfigValidatorTest.java`:
rejects a null group, a blank media type, a non-structure media type, a media type without a
`version` parameter, an empty `dataflows` list, and a malformed dataflow reference.

### Manual verification

To check a cold start against live OECD, start the proxy with the bundled configuration and
preheat enabled, and watch the preheat summary line:

```powershell
$env:SDMXPROXY_PREHEAT_ENABLED='true'; ./gradlew :sdmx-proxy:bootRun *> proxy-preheat.log
```

Expected: a `Preheat run` summary with 19 OECD dataflows warmed, about 146 split entries, and
about 38 upstream calls, and `sdmxproxy.registry.structure_requests` for OECD at about 38. The
run takes 2 to 4 minutes.

After the run, the following request is a ready-response hit with no OECD call:

```bash
curl -s -o /dev/null -w "%{http_code} %{time_total}\n" -H "Accept: application/vnd.sdmx.structure+xml;version=2.1" -H "X-Source-Artefact-Urn: urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=OECD.SDD.TPS:DSD_BATIS(1.0)" "http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/codelist/SDMX/CL_FREQ/2.1"
```

Expected: `200` in milliseconds, and no `Single-flight: calling registry OECD` line for it in the
log.

Repeat the StatGPT admin cold load from the evidence section against the prewarmed proxy.
Expected: no `Broken pipe` errors and no `Single-flight: calling registry OECD` line for any
codelist, concept scheme, or `latest?references=datastructure` request during the load. Any
such line points to a key mismatch (edge case 13). Record the heap used after the run (edge
case 10).

### E2E tests

Preheat is off by default, so the E2E `bootRun` doesn't run it. Leave `SDMXPROXY_PREHEAT_ENABLED`
unset for E2E runs. The fetcher's negative cache is the only change visible to E2E. Rerun the
registry suites as a regression check:

```powershell
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.OECD_2_1_RegistryTestSuit" --tests "*.IMF_3_0_RegistryTestSuit" --tests "*.BIS_3_0_RegistryTestSuit"
```

Expected: the same pass counts as before the change.

## SDMX standard references

- Structure queries and `references=descendants`: `sdmx-rest-2.2.0/doc/structures.md`.
- sdmx-core sources used by preheat and the splitter:
  - `sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/model/beans/SdmxBeans.java`
    (`getCodelists`, `getConceptSchemes`, `getDataflows`)
  - `sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/model/beans/base/MaintainableBean.java`
  - `sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/model/beans/datastructure/DataflowBean.java`
    (`getDataStructureRef`)
  - `sdmx-core-2.3.9/fusion-api-sdmx/src/main/java/io/sdmx/api/sdmx/model/beans/reference/ICrossReferenceBeanBase.java`
    (`getTargetUrn`)
  - `sdmx-core-2.3.9/fusion-sdmx-im/src/main/java/io/sdmx/im/beans/container/SdmxBeansImpl.java`

## Open questions

1. **Heap budget.** What heap limit does the deployment set, and is a few hundred megabytes of
   structure cache acceptable in LOCAL mode? Answer during manual verification.
2. **Pacing against live traffic.** `upstreamInterval` of 4 seconds leaves OECD quota for live
   traffic only if StatGPT is idle during startup. If not, raise it.

The review log moved to [evidence/review-log.md](evidence/review-log.md) during the OpenSpec migration.
