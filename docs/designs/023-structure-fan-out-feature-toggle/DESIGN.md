# Design 023: Structure fan-out as a toggled feature

**Status:** draft (2026-05-20). Awaiting review.

## Context

Structure fan-out -- "when `agencyID=*`, query every configured registry in
parallel and merge the structures into one response" -- existed in the
GitLab predecessor of this repo from January through March 2026:

- Added: GitLab commit `443659a [63] - add fanout; ...` (2026-01-12). The
  original design lives at `docs/FAN_OUT_DESIGN.md` in the current repo
  (kept as historical reference).
- Removed: GitLab commit `a8da138 [109] - Config Server and Agency Routing`
  (2026-03-20). Same commit introduced `UnsupportedAgencyWildcardException`
  + `AgencySchemeService`, redirecting clients to a discovery-then-query
  workflow.
- The GitHub repo never carried fan-out; its first commit (`7e697b8`,
  2026-04-03) already had the rejection in place. The contract was
  hardened by `40e52c5 fix: Fix E2E tests` (2026-05-12), which deleted the
  parameterised generic-structure case in `BaseRegistryTestSuite` and added
  `StructureWildcardE2ETest` asserting HTTP 501 for `agencyID=*`.

The use case has resurfaced: clients want a single broad query that returns
"everything from everywhere" without the round-trip through
`/structure/agencyscheme`. We need to re-enable fan-out, but without
silently breaking the contract that current deployments depend on. The
right shape is an opt-in toggle.

## Problem

Today `QueryTranslatorImpl.translateStructureQuery` at
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java:161`
rejects any wildcard or comma-separated `agencyId` with HTTP 501:

```java
if ("*".equals(agencyId) || (agencyId != null && agencyId.contains(","))) {
    throw new UnsupportedAgencyWildcardException(
        "Wildcard and comma-separated agency queries are not supported. " +
        "Use /structure/agencyscheme to discover agencies."
    );
}
```

That rule applies uniformly to every deployment. There is no way to enable
fan-out on a single environment for evaluation, and no way for a client
that *prefers* the old broad-query shape to opt in.

## Non-goals

- **Comma-separated agency fan-out** (`BIS,IMF`). The old implementation
  walked all configured registries to discover which one(s) held an
  arbitrary agency ID. Today's `AgencyRoutingServiceImpl.resolveRegistry`
  requires an explicit `AgencyConfiguration` entry with `primaryRegistry`
  and throws `AgencyRoutingException` otherwise -- there is no "look in
  every registry's agency list" path. Reviving that needs either (a) a new
  discovery API on `AgencyRoutingService` or (b) reusing the
  `AgencySchemeService.discoverSubAgencies` machinery, both of which are
  larger than this change. Comma-separated stays rejected (501) when
  fan-out is enabled.
- **Fan-out for data or availability queries.** Data fan-out is conceptually
  different (you have to know *which* dataflow), and availability queries
  already require a specific dataflow URN. Keep scope to `/structure`.
- **A new cache *domain*.** The merged fan-out response is cached, but it
  rides on the existing ready-response cache (`CacheService.getReadyResponse`
  / `putReadyResponse`); no new TTL field, no new properties bean, no new
  invalidation pathway. See "Merged-response caching" below for the key
  shape.
- **A new exception family.** Reuse the post-020 hierarchy
  (`BaseException` -> `ServiceUnavailableException`) for the
  all-registries-failed case.

## Current Architecture

Single-registry structure query path today:

```
SdmxStructure30Controller.getResources(agencyId, ...)
    -> QueryTranslator.translateStructureQuery(...)              [throws 501 if agencyId == "*"]
       -> AgencyRoutingService.resolveRegistry(agencyId, urn)    [returns single RegistryConfiguration]
       -> selectRegistryAndVersion -> RegistrySelectionResult
       -> TranslatedStructureQuery (one registry, one version)
    -> AdapterRouter.getStructures(query)                        [returns StreamingResponseBody]
       -> CacheService.getReadyResponse / putReadyResponse       [response cache by registryName]
       -> bypass OR convert
          -> GenericRegistryAdapter.getStructures(query)
          -> StreamingStructureConversionService.convert(...)
```

Discovery alternative (already in place):

```
GET /structure/agencyscheme  -> AgencySchemeController -> AgencySchemeService
                              [fans out internally to enumerate sub-agencies
                               via discoverSubAgencies + getSdmxBeans per agency,
                               but the public endpoint returns only the merged
                               agency list, not the underlying structures]
```

## Solution

Reintroduce fan-out for the `agencyId == "*"` case only, gated by a single
top-level boolean on `ProxyConfiguration` named `structureFanOutEnabled`
(default `false`). The control surface is:

- **Disabled (default):** `agencyId == "*"` -> HTTP 501
  (`UnsupportedAgencyWildcardException`, unchanged). Existing
  `StructureWildcardE2ETest` keeps passing.
- **Enabled:** `agencyId == "*"` -> proxy issues one structure query per
  configured `RegistryConfiguration` that supports the requested
  `structureType`, in parallel; results are merged via `SdmxBeans.merge`
  and serialized through the existing
  `StreamingStructureConversionService.convert(SdmxBeans, OutputStream,
  MediaType)` path.
- **Comma-separated agencyId** stays rejected with 501 in both modes (see
  Non-goals). The toggle is named *structure fan-out* (not "wildcard"),
  because if/when comma-separated support is added it lives under the same
  flag.

Implementation mirrors the GitLab `443659a` approach with three deltas
required by the current codebase:

1. The exception hierarchy is now the post-020 one, so
   `StructureFanOutException` extends `ServiceUnavailableException`
   (HTTP 503 -- "all upstream sources unavailable" is a transient
   infrastructure failure, not a client error). The old version extended
   raw `RuntimeException` and surfaced as HTTP 500.
2. The conversion service signature is now
   `convert(SdmxBeans, OutputStream, MediaType)`; the old call site passed
   `String contentType`. Already compatible.
3. Registry selection now goes through `AgencyRoutingService`. Fan-out
   bypasses it entirely (it picks `RegistryConfiguration`s directly from
   `ProxyConfiguration.getConfigs()`), so the routing service stays
   untouched.

### Why a boolean toggle (not per-registry / per-agency)

- Fan-out is a *global* request-shape capability, not a per-registry
  property: when enabled, every registry that supports the requested
  structure type participates.
- A per-registry "include in fan-out" flag was considered. It does not
  add value today (every configured registry should participate when
  the operator turns fan-out on) and complicates the toggle semantics.
  Easy to add later if needed.
- A request-level header (e.g. `X-Fan-Out: true`) was considered.
  Rejected because the decision is operational ("is this proxy
  deployment allowed to fan out to all configured registries?"), not
  per-request. Clients that want a single registry can still target
  it by agency.

### Why default `false`

The current 501 contract is observable by clients (and codified in
`StructureWildcardE2ETest`). Defaulting to `false` preserves it for every
deployment that doesn't explicitly opt in. Existing JSON configs without
the new field deserialize to `false` for free (Lombok `@Data` boolean
default).

### Parallel execution and failure semantics

Match the old behaviour:

- One thread per participating registry, via `Executors.newFixedThreadPool(n)`
  inside a try-with-resources. The pool is created per request (matches
  GitLab `443659a` design -- fan-out is rare, no need for a shared pool).
- Each leg calls `AdapterRouter.getSdmxBeans(query)` so the parsed-structures
  cache absorbs repeated `*` requests at the per-registry level.
- A leg that throws is logged at WARN with the registry name and swallowed.
  Surviving legs still produce a response.
- If *every* leg fails -> throw `StructureFanOutException` (503). Naming
  the failed registries in the message is operator-useful and contains no
  client-sensitive data, so the subclass overrides `getClientMessage()` to
  echo the detail (same pattern as `IllegalRegistryConfigurationException`).

### Merged-response caching

The expensive part of a fan-out request is *not* the per-leg fetch (those
are already cached as parsed structures): it is the parse-merge-serialize
of N legs into a single response document on every hit. That work is
deterministic in `(structureType, resourceId, version, references, detail,
Accept, set-of-configured-registries)`, so we cache the final byte buffer
under a fan-out-specific key in the existing ready-response cache.

**Key shape:**

```
response:structure:fanout:{type}:{resourceId}:{version}:{references}:{detail}:{md5(Accept + configsHash)}
```

`configsHash` is `configurationProvider.getConfiguration().getConfigs().hashCode()`
-- the same trick `AgencySchemeService.buildCacheKey` uses on `agencies`.
When the operator pushes a new config (adds/removes/reorders registries)
the hash changes and existing fan-out entries are orphaned (they expire on
TTL; no explicit eviction needed). The `fanout` segment is a literal
distinguisher so a key dump immediately separates fan-out responses from
single-registry ones; there is no `{registryName}` slot here because the
response aggregates across all of them.

**Storage:** identical to single-registry ready responses --
`CacheService.putReadyResponse(key, bytes)` and `getReadyResponse(key)`.
The existing TTL (default 6h, `ReadyResponsesProperties`) and jitter
apply. No new properties, no new cache domain.

**Write policy:** the merged response is written to cache only when
**every** participating leg succeeded. Partial responses (some legs failed,
others succeeded) are streamed to the client but **not** cached. A
transient outage on one registry would otherwise produce a partial entry
that pins for the full TTL and quietly omits a registry's content from
every subsequent fan-out hit.

**Read policy:** check the cache before translating queries. A hit
short-circuits `translateWildcardStructureFanOut` entirely -- we do not
need the `List<TranslatedStructureQuery>` to serve a cached response. This
also means a hit avoids re-parsing the Accept header beyond what's needed
to derive `contentType` for the `ResponseEntity`.

### Version selection inside a fan-out leg

For each candidate `RegistryConfiguration`, pick a
`VersionSpecificRegistryConfiguration` that supports the requested
`structureType`. The selection is:

1. If the parsed `Accept` header pinned a desired version, try that first.
2. Otherwise prefer SDMX 3.0, fall back to SDMX 2.1.
3. Inside the chosen version, the structure type must appear in
   `structureEndpointConfig.supportedStructures`.

A registry that has no version supporting the structure type is skipped
silently (it's not a failure -- it's "this registry has nothing relevant
to contribute"). Same rule as the old `selectVersionForStructureType`.

The SDMX 2.1 wildcard translation (`*` -> `all`) is already handled by the
existing `getVersionSpecificQueryId` helper.

## Implementation Plan

### Step 1: Configuration field

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ProxyConfiguration.java`

Add a single boolean field. Lombok `@Data` provides the getter/setter.

```java
@Data
public class ProxyConfiguration {

    private List<RegistryConfiguration> configs;

    private List<AgencyConfiguration> agencies;

    /**
     * When true, a structure query with {@code agencyId="*"} is fanned out to every
     * configured registry that supports the requested structure type, and results
     * are merged. When false (default), wildcard agency requests return HTTP 501.
     * Comma-separated agency IDs are rejected with 501 in both modes.
     */
    private boolean structureFanOutEnabled;
}
```

**Key points:**

- Boolean primitive (not `Boolean` boxed): missing-from-JSON deserializes
  to `false`, matching the explicit `false` default.
- Per CLAUDE.md: the schema table in `sdmx-proxy-config/README.md` must be
  updated in the same change (see Step 7).

### Step 2: Restore `StructureFanOutException`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/StructureFanOutException.java` (new)

Re-parent onto the current hierarchy. The message names which registries
failed, and that detail is operator-useful + client-safe, so override
`getClientMessage()`.

```java
package com.epam.sdmxproxy.exception;

import java.util.List;

/**
 * Thrown when every registry leg of a structure fan-out request failed.
 * Maps to HTTP 503 via the {@link ServiceUnavailableException} family --
 * this is an upstream-infrastructure failure, not a client error.
 *
 * <p>The detail message lists the failed registry names; that is curated
 * and safe to echo, so {@link #getClientMessage()} returns it instead of
 * the family-default generic message.
 */
public class StructureFanOutException extends ServiceUnavailableException {

    private final List<String> failedRegistries;

    public StructureFanOutException(String message, List<String> failedRegistries) {
        super(message);
        this.failedRegistries = List.copyOf(failedRegistries);
    }

    public List<String> getFailedRegistries() {
        return failedRegistries;
    }

    @Override
    public String getClientMessage() {
        return getMessage();
    }
}
```

### Step 3: Translator interface -- add fan-out translation method

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslator.java`

Add one method. The wildcard rejection in `translateStructureQuery` *stays*
-- the controller routes wildcard requests to the new method when the
toggle is on, and to the existing method (which throws 501) when it's off.

```java
/**
 * Translates a wildcard structure query ({@code agencyId="*"}) into one
 * {@link TranslatedStructureQuery} per registry that supports the requested
 * structure type. Caller is responsible for checking the
 * {@code structureFanOutEnabled} toggle before invoking.
 *
 * <p>Returns an empty list if no configured registry supports the structure
 * type. Callers should treat that as an empty merged response, not a failure.
 *
 * @param structureType structure type (e.g. "datastructure", "codelist")
 * @param resourceID    resource ID (may be {@code null} or {@code "*"})
 * @param version       version (may be {@code null} or {@code "*"})
 * @param references    references parameter, pass-through
 * @param detail        detail parameter, pass-through
 * @param acceptHeader  client Accept header (drives content type + version selection)
 * @return one translated query per participating registry; possibly empty
 */
List<TranslatedStructureQuery> translateWildcardStructureFanOut(
        String structureType,
        String resourceID,
        String version,
        String references,
        String detail,
        String acceptHeader
);
```

**Key points:**

- Name reflects the *only* scope handled (wildcard). Comma-separated is
  not in this method's signature on purpose -- adding it later is a new
  method or an enum-typed parameter.
- No `sourceArtefactUrn` parameter: source-artefact routing only makes
  sense for a single agency.

### Step 4: Translator implementation

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

Two changes:

(a) Inject `ProxyConfigurationProvider` (currently the translator does *not*
hold one -- registry resolution goes through `AgencyRoutingService`, which
does). Fan-out needs to walk *all* registries, so it needs the provider.

```java
private final ProxyConfigurationProvider configurationProvider;
private final AgencyRoutingService agencyRoutingService;
private final FilterValidator filterValidationService;
// ...existing fields
```

(b) Implement the new method. The wildcard rejection in
`translateStructureQuery` is unchanged; the controller decides which
method to call.

```java
@Override
public List<TranslatedStructureQuery> translateWildcardStructureFanOut(
        String structureType,
        String resourceId,
        String version,
        String references,
        String detail,
        String acceptHeader
) {
    MediaTypeParseResult parsedMediaType = parseMediaType(acceptHeader);
    ProxyConfiguration configuration = configurationProvider.getConfiguration();

    List<TranslatedStructureQuery> queries = new ArrayList<>();
    for (RegistryConfiguration registryConfig : configuration.getConfigs()) {
        VersionSpecificRegistryConfiguration versionConfig =
                selectVersionForStructureType(registryConfig, structureType, parsedMediaType.getSdmxVersion());
        if (versionConfig == null) {
            continue;
        }

        RegistrySelectionResult selected = RegistrySelectionResult.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .build();
        ReturnFormat returnFormat = determineStructureReturnFormat(selected, parsedMediaType);

        queries.add(TranslatedStructureQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .structure(getStructure(versionConfig, structureType, SDMX_30_ALL_WILDCARD,
                        getVersionSpecificQueryId(resourceId, versionConfig),
                        getVersionSpecificQueryId(version, versionConfig)))
                .references(references)
                .detail(detail)
                .contentType(parsedMediaType.getMediaType())
                .registryReturnFormat(returnFormat)
                .build());
    }
    return queries;
}

private VersionSpecificRegistryConfiguration selectVersionForStructureType(
        RegistryConfiguration registryConfig,
        String structureType,
        SdmxVersion desiredVersion
) {
    if (desiredVersion != null) {
        VersionSpecificRegistryConfiguration v = registryConfig.getVersionConfiguration(desiredVersion);
        if (v != null && supportsStructureType(v, structureType)) {
            return v;
        }
    }
    VersionSpecificRegistryConfiguration v30 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0);
    if (v30 != null && supportsStructureType(v30, structureType)) {
        return v30;
    }
    VersionSpecificRegistryConfiguration v21 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_2_1);
    if (v21 != null && supportsStructureType(v21, structureType)) {
        return v21;
    }
    return null;
}

private static boolean supportsStructureType(VersionSpecificRegistryConfiguration v, String type) {
    StructureEndpointConfiguration cfg = v.getStructureEndpointConfig();
    return cfg != null && cfg.getSupportedStructures() != null
            && cfg.getSupportedStructures().contains(type);
}
```

**Key points:**

- Reuses `selectVersionForStructureType` shape from GitLab `443659a` but
  inlined here rather than as a public helper -- it's an internal concern
  of the translator.
- `getVersionSpecificQueryId` already handles the `*` -> `all` translation
  for SDMX 2.1.
- The `SDMX_30_ALL_WILDCARD` constant is already defined on the class.

### Step 5: CacheKeyGenerator -- fan-out key

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java`

Add a sibling to `generateResponseKey` that builds the fan-out key directly
from request fields (no `TranslatedStructureQuery` -- fan-out doesn't have
one until after translation, and the cache check happens before translation).

```java
private static final String FAN_OUT_RESPONSE_KEY_PREFIX = "response:structure:fanout:";

/**
 * Generate cache key for the merged response of a wildcard fan-out structure query.
 * Format: response:structure:fanout:{type}:{resourceId}:{version}:{references}:{detail}:{md5(Accept + configsHash)}
 *
 * <p>{@code configsHash} should reflect the set of configured registries
 * (typically {@code configurationProvider.getConfiguration().getConfigs().hashCode()}).
 * When the operator updates the registry list the hash changes and existing
 * entries are orphaned (no explicit invalidation needed -- they expire on TTL).
 */
public static String generateFanOutResponseKey(
        String structureType,
        String resourceId,
        String version,
        String references,
        String detail,
        MediaType requestedMediaType,
        int configsHash) {

    String accept = requestedMediaType != null ? requestedMediaType.toString() : "";
    String fingerprint = "Accept:" + accept + "&configsHash:" + configsHash;
    String hash = md5Hash(fingerprint);

    return FAN_OUT_RESPONSE_KEY_PREFIX
            + structureType + KEY_SEPARATOR
            + (resourceId != null ? resourceId : "") + KEY_SEPARATOR
            + (version != null ? version : "") + KEY_SEPARATOR
            + (references != null ? references : "") + KEY_SEPARATOR
            + (detail != null ? detail : "") + KEY_SEPARATOR
            + hash;
}
```

**Key points:**

- No `registryName` slot: the response aggregates all of them. The literal
  `fanout` segment distinguishes from single-registry response keys at a
  glance in any key dump.
- Reuses `md5Hash` and `KEY_SEPARATOR` from the existing utility.
- `MediaType` import is already present (used by `generateResponseKey`).

### Step 6: AdapterRouter -- new fan-out streaming method

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouter.java`

Add the new method. The signature carries the cache key separately because
fan-out cache lookup happens *before* translation (so the controller
computes the key, checks the cache, and only calls the translator + router
on a miss):

```java
/**
 * @param queries     per-registry queries returned by
 *                    {@link QueryTranslator#translateWildcardStructureFanOut}
 * @param responseKey cache key for the merged response (computed by the
 *                    controller via {@link CacheKeyGenerator#generateFanOutResponseKey})
 */
StreamingResponseBody getStructuresWithFanOut(List<TranslatedStructureQuery> queries, String responseKey);
```

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

The implementation. The merged result is written to the ready-response
cache *only if every leg succeeded*.

```java
@Override
public StreamingResponseBody getStructuresWithFanOut(List<TranslatedStructureQuery> queries, String responseKey) {
    if (queries == null || queries.isEmpty()) {
        // Controller short-circuits empty fan-out before reaching here.
        throw new UnexpectedStateException("getStructuresWithFanOut called with empty queries");
    }
    MediaType contentType = queries.getFirst().getContentType();
    return outputStream -> {
        try (ExecutorService executor = Executors.newFixedThreadPool(queries.size())) {
            FanOutLegResult legs = runFanOutLegs(queries, executor);
            SdmxBeans merged = mergeFanOutResults(legs.successes());

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            streamingStructureConversionService.convert(merged, buffer, contentType);
            byte[] bytes = buffer.toByteArray();
            outputStream.write(bytes);

            if (legs.failedRegistries().isEmpty()) {
                cacheService.putReadyResponse(responseKey, bytes);
            } else {
                log.debug("Fan-out response NOT cached for {} -- {} leg(s) failed: {}",
                        responseKey, legs.failedRegistries().size(), legs.failedRegistries());
            }
        }
    };
}

private FanOutLegResult runFanOutLegs(List<TranslatedStructureQuery> queries, ExecutorService executor) {
    List<CompletableFuture<Optional<SdmxBeans>>> futures = queries.stream()
            .map(q -> CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.of(getSdmxBeans(q));
                } catch (Exception e) {
                    log.warn("Fan-out leg failed for registry {}: {}",
                            q.getRegistryConfiguration().getName(), e.getMessage(), e);
                    return Optional.<SdmxBeans>empty();
                }
            }, executor))
            .toList();

    List<SdmxBeans> ok = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
        Optional<SdmxBeans> result = futures.get(i).join();
        if (result.isPresent()) {
            ok.add(result.get());
        } else {
            failed.add(queries.get(i).getRegistryConfiguration().getName());
        }
    }
    if (ok.isEmpty()) {
        throw new StructureFanOutException(
                "Structure fan-out failed: every registry leg failed (" + String.join(", ", failed) + ")",
                failed);
    }
    return new FanOutLegResult(ok, failed);
}

private static SdmxBeans mergeFanOutResults(List<SdmxBeans> partials) {
    SdmxBeans merged = new SdmxBeansImpl();
    partials.forEach(merged::merge);
    return merged;
}

private record FanOutLegResult(List<SdmxBeans> successes, List<String> failedRegistries) {}
```

**Key points:**

- Reuses `getSdmxBeans(query)` from the same class, which is the parsed-
  structures cache populator. So per-registry parsed structures stay
  warm; the new fan-out cache piggybacks on top.
- The merged response is materialized in a `ByteArrayOutputStream` so it
  can be cached *and* streamed in one pass. This mirrors how
  `getStructuresConversion` does it for single-registry responses
  (`AdapterRouterImpl.getStructuresConversion`).
- Cache write is gated on "no failed legs". A partial response is
  served but not pinned -- the next request retries the failed legs.
- `SdmxBeansImpl` import is already present in this file (used by the
  empty-structures fallback at line ~199).
- Try-with-resources on the `ExecutorService`: closes/awaits termination
  on scope exit (Java 19+ `AutoCloseable.close()` calls
  `shutdown()` + `awaitTermination`). Matches the GitLab `443659a` pattern.
- Per-request executor sized to the number of legs. Fan-out is rare; no
  shared thread pool needed.
- One-failure-is-fine, all-failures-throws semantics match the old behaviour.

### Step 7: Controller wiring

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/SdmxStructure30Controller.java`

Inject `ProxyConfigurationProvider` and `CacheService`, branch on the
toggle, check the fan-out cache before translating. The single-agency path
is unchanged.

```java
public class SdmxStructure30Controller implements SdmxStructure30Api {

    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private final ProxyConfigurationProvider configurationProvider;
    private final CacheService cacheService;
    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE;

    @Override
    public ResponseEntity<StreamingResponseBody> getResources(
            @PathVariable("structureType") String structureType,
            @PathVariable("agencyId") String agencyId,
            @PathVariable("resourceId") String resourceId,
            @PathVariable("version") String version,
            @RequestParam(value = "references", required = false) @Nullable String references,
            @RequestParam(value = "detail", required = false, defaultValue = "full") String detail,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept,
            @RequestHeader(value = "X-Source-Artefact-Urn", required = false) @Nullable String sourceArtefactUrn
    ) {
        logRequestUrl(accept, STRUCTURE);

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        ProxyConfiguration config = configurationProvider.getConfiguration();
        if ("*".equals(agencyId) && config.isStructureFanOutEnabled()) {
            return fanOutResponse(structureType, resourceId, version, references, detail, accept, config);
        }

        TranslatedStructureQuery structureQuery = queryTranslator.translateStructureQuery(
                structureType, agencyId, resourceId, version, references, detail, accept, sourceArtefactUrn);

        return ResponseEntity.ok()
                .contentType(structureQuery.getContentType())
                .body(adapterRouter.getStructures(structureQuery));
    }

    private ResponseEntity<StreamingResponseBody> fanOutResponse(
            String structureType, String resourceId, String version,
            @Nullable String references, String detail, @Nullable String accept,
            ProxyConfiguration config) {
        MediaType contentType = SdmxMediaType.parseMediaType(accept).getMediaType();
        String cacheKey = CacheKeyGenerator.generateFanOutResponseKey(
                structureType, resourceId, version, references, detail, contentType,
                config.getConfigs() != null ? config.getConfigs().hashCode() : 0);

        Optional<byte[]> cached = cacheService.getReadyResponse(cacheKey);
        if (cached.isPresent()) {
            log.debug("Fan-out cache hit: {}", cacheKey);
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(outputStream -> outputStream.write(cached.get()));
        }
        log.debug("Fan-out cache miss: {}", cacheKey);

        List<TranslatedStructureQuery> queries = queryTranslator.translateWildcardStructureFanOut(
                structureType, resourceId, version, references, detail, accept);
        if (queries.isEmpty()) {
            // No configured registry supports the requested structure type.
            // Same semantics as the old code: empty result, not an error.
            // Don't cache empty bodies -- they're cheap to produce and the
            // emptiness is config-derived, not registry-derived.
            return ResponseEntity.ok().contentType(contentType).body(outputStream -> {});
        }
        return ResponseEntity.ok()
                .contentType(queries.getFirst().getContentType())
                .body(adapterRouter.getStructuresWithFanOut(queries, cacheKey));
    }
}
```

**Key points:**

- Cache lookup happens **before** translation, so a hit avoids walking
  `configs` and building per-leg `TranslatedStructureQuery` objects. The
  `contentType` for the response is parsed straight from the Accept
  header (cheap).
- The toggle is read on each request. `ProxyConfigurationProvider` already
  serves the latest configuration (config server pushes update it
  in-place), so toggling fan-out at runtime works without restart -- and
  matches how every other config field behaves.
- `configsHash` is `config.getConfigs().hashCode()`. When the operator
  pushes a new config, the hash changes and existing fan-out cache
  entries are orphaned (they expire on TTL).
- Branch is only taken for `"*".equals(agencyId)`. Comma-separated agencyId
  still hits the existing translator, which still throws
  `UnsupportedAgencyWildcardException` -> 501. That keeps the 501
  contract for the case we explicitly don't support yet.
- Empty-queries handling: short-circuit to an empty body, not cached
  (cheap to produce; would otherwise pin an empty entry that obscures a
  recovered registry config).

### Step 8: README schema update

**File:** `sdmx-proxy-config/README.md`

Add the new field to the `ProxyConfiguration` table (after `agencies`):

| Field                       | Required | Description                                                                                          | Available Values | Default |
|-----------------------------|:--------:|------------------------------------------------------------------------------------------------------|------------------|---------|
| `structureFanOutEnabled`    |    No    | When true, `GET /structure/{type}/*/.../...` is fanned out to every registry that supports the type. Comma-separated agency IDs stay rejected. | `true`, `false`  | `false` |

Per CLAUDE.md the schema table must be updated in the same change as the
config-class field.

## No Changes Required

These files need **no modification**:

- **`AgencyRoutingService` / `AgencyRoutingServiceImpl`** -- fan-out walks
  `ProxyConfiguration.configs` directly. Single-registry resolution is
  unchanged.
- **`AgencySchemeService`** -- the discovery endpoint is independent. We
  keep it as the recommended path for clients that want to enumerate
  agencies first.
- **`CacheService`** -- only the interface consumers change.
  `putReadyResponse` / `getReadyResponse` are reused as-is; no new TTL
  field, no new properties bean.
- **`GlobalExceptionHandler`** -- `StructureFanOutException` extends
  `ServiceUnavailableException`, so the existing family handler picks it up.
- **`StreamingStructureConversionService`** -- the existing
  `convert(SdmxBeans, OutputStream, MediaType)` overload is the merge-and-
  serialize path. No changes.
- **`UnsupportedAgencyWildcardException`** -- still thrown for the
  comma-separated case and for `*` when the toggle is off. Same class.

## Files Affected

| File | Change Type | Description |
|------|-------------|-------------|
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ProxyConfiguration.java` | Modified | Add `structureFanOutEnabled` boolean |
| `sdmx-proxy-config/README.md` | Modified | Document the new field |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/StructureFanOutException.java` | New | All-registries-failed exception (extends `ServiceUnavailableException`) |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslator.java` | Modified | Add `translateWildcardStructureFanOut(...)` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java` | Modified | Inject `ProxyConfigurationProvider`; implement new method; add `selectVersionForStructureType` helper |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java` | Modified | Add `generateFanOutResponseKey(...)` and `FAN_OUT_RESPONSE_KEY_PREFIX` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouter.java` | Modified | Add `getStructuresWithFanOut(queries, responseKey)` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java` | Modified | Implement fan-out: parallel `getSdmxBeans` per leg, merge, serialize, write to ready-response cache only when every leg succeeded |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/SdmxStructure30Controller.java` | Modified | Inject `ProxyConfigurationProvider` + `CacheService`; cache-check before translating; branch on toggle for `agencyId="*"` |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java` | Modified | Tests for `translateWildcardStructureFanOut` (see Verification) |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/cache/CacheKeyGeneratorTest.java` | Modified | Tests for `generateFanOutResponseKey` (stable across input order, varies on Accept / configsHash / structureType) |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImplTest.java` | Modified | Tests for `getStructuresWithFanOut` happy / partial / all-failed paths, plus cache-write gated on all-success |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/StructureWildcardE2ETest.java` | Modified | Split into toggle-off + toggle-on (single registry) + toggle-on (multi registry) suites |

## Edge Cases

1. **Toggle enabled but `configs` is empty.** `translateWildcardStructureFanOut`
   returns an empty list; controller short-circuits to an empty 200 body.
   No error.
2. **Toggle enabled but no configured registry supports the requested
   structure type.** Same as (1): empty list -> empty 200. Avoids
   misleading 503.
3. **Toggle enabled, every leg returns 4xx / 5xx from upstream.** Each leg
   throws (`FeignException`), gets swallowed at the future, all-failed
   guard fires -> `StructureFanOutException` (HTTP 503 with the registry
   names). The client retries / inspects the message.
4. **Toggle enabled, mixed success/failure.** Surviving legs merge and
   serve. The client sees a partial response (a registry the operator
   thought was contributing may be silently absent). The leg failure is
   logged at WARN; that is the operator's visibility hook. We do *not*
   add a response header listing failed registries -- it would leak
   registry topology to the client. **The merged response is not written
   to cache** in this case; the next identical request retries the failed
   legs.
5. **`agencyId="*"` with `resourceId="ABC"`.** Path is allowed: each leg
   asks its registry for "any agency's ABC". Most registries return empty
   for this; merge is still fine. No special handling required.
6. **`agencyId="*"` with `sourceArtefactUrn` header.** Header is ignored
   for fan-out (it only makes sense for single-agency source routing).
   The controller does not pass it into `translateWildcardStructureFanOut`.
7. **Comma-separated agency with toggle on or off.** Same 501 in both
   modes. The branch only triggers on `"*".equals(agencyId)`.
8. **Cache behaviour across toggle and config flips.**
   - *Toggle on -> off:* fan-out cache entries become orphaned. They sit
     in the ready-response cache until TTL but are never read (the
     controller stops branching into the fan-out path). No staleness risk.
   - *Toggle off -> on:* fan-out cache starts cold; first request after
     the flip pays the full N-leg cost. Per-registry parsed-structures
     cache may already be warm from prior single-agency requests.
   - *Registries added / removed in config:* `configsHash` changes, so
     existing fan-out entries are unreachable by new requests (different
     key). Same orphan-until-TTL behaviour.
   - *Per-registry parsed-structures cache* is unaffected by all of the
     above -- it's keyed on `registryName` and survives toggle changes.
9. **Two legs return the same artefact (same agency/id/version, different
   registry).** `SdmxBeansImpl.merge` overwrites on key collision -- the
   last leg wins, which is non-deterministic with the current futures
   layout. Acceptable for the rare case where two registries publish the
   same artefact; matches the old behaviour. If determinism is needed
   later, sort legs by registry name before merging.
10. **Toggle absent from a deployed JSON config.** Boolean primitive
    deserializes to `false`. Existing deployments are unaffected.
11. **Fan-out cache hit after a partial-success miss.** Cannot happen:
    partial-success responses are not cached (edge case 4). The next
    request goes through the cache miss path again and retries the
    failed legs.
12. **`configsHash` collisions.** `List.hashCode()` on a list of POJOs
    with overridden `equals` is sufficient for invalidation purposes:
    a meaningful change to the registry set (add/remove/reorder, or any
    field change inside `RegistryConfiguration`) flips the hash with
    overwhelming probability. The cost of a false-same-hash is a stale
    cache entry serving a now-removed registry's content for the
    remaining TTL -- not corrupting state, just slightly stale.

## Verification

### Unit Tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java`

| Test Method | Description |
|-------------|-------------|
| `translateWildcardStructureFanOut_returnsOneQueryPerSupportingRegistry()` | Configure 3 registries; 2 support `datastructure`, 1 only supports `codelist`. Expect 2 queries when called with `datastructure`. |
| `translateWildcardStructureFanOut_returnsEmpty_whenNoRegistrySupportsType()` | All registries omit the requested structure type from `supportedStructures`. Expect empty list. |
| `translateWildcardStructureFanOut_prefers30_thenFallsBackTo21()` | Registry has both versions and both support the type; expect 3.0 chosen when no Accept version pin. |
| `translateWildcardStructureFanOut_honoursAcceptVersion()` | Accept pins SDMX 2.1; expect 2.1 chosen on a registry that has both. |
| `translateWildcardStructureFanOut_translatesAllWildcardForV21()` | Resource/version `*` on a 2.1-only registry is sent as `all`. |
| `translateStructureQuery_stillThrows501_forWildcard()` | Sanity: the existing path stays the same; controller is responsible for routing toggle-on requests away from this method. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/cache/CacheKeyGeneratorTest.java`

| Test Method | Description |
|-------------|-------------|
| `generateFanOutResponseKey_isStableForSameInputs()` | Two calls with identical args yield identical keys. |
| `generateFanOutResponseKey_differsByStructureType()` | Same args, different `structureType` -> different keys. |
| `generateFanOutResponseKey_differsByMediaType()` | Same args, different Accept media type -> different keys. |
| `generateFanOutResponseKey_differsByConfigsHash()` | Same args, different `configsHash` -> different keys. (Simulates a config change.) |
| `generateFanOutResponseKey_handlesNullResourceVersionRefDetail()` | Nulls in optional fields serialize as empty segments, not "null". |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImplTest.java`

| Test Method | Description |
|-------------|-------------|
| `getStructuresWithFanOut_mergesBeansFromAllLegs()` | Mock `getSdmxBeans` for each of 3 queries; assert merged output streamed via `convert`. |
| `getStructuresWithFanOut_swallowsPerLegFailures_whenSomeSucceed()` | 2 legs throw, 1 succeeds; result is the single surviving bean set. Assert WARN log emitted for the failures. |
| `getStructuresWithFanOut_throwsFanOutException_whenAllLegsFail()` | Every leg throws; expect `StructureFanOutException` with all registry names in `getFailedRegistries()`. |
| `getStructuresWithFanOut_writesCache_whenAllLegsSucceed()` | All legs return beans; assert `cacheService.putReadyResponse(responseKey, serializedBytes)` called once with the serialized merged response. |
| `getStructuresWithFanOut_skipsCache_whenAnyLegFails()` | 2 legs succeed, 1 fails; assert `cacheService.putReadyResponse` NOT called. |
| `getStructuresWithFanOut_emptyList_throwsUnexpectedState()` | Defensive: empty input is a programming error (controller short-circuits). |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/controller/SdmxStructure30ControllerTest.java` (extend or add)

| Test Method | Description |
|-------------|-------------|
| `wildcardAgency_toggleOff_throws501()` | Configuration has `structureFanOutEnabled=false`; assert `UnsupportedAgencyWildcardException`. |
| `wildcardAgency_toggleOn_cacheHit_servesFromCache()` | `structureFanOutEnabled=true`, cache returns bytes; verify `translateWildcardStructureFanOut` NOT invoked and the cached bytes are written to the response body. |
| `wildcardAgency_toggleOn_cacheMiss_invokesFanOut()` | `structureFanOutEnabled=true`, cache miss; verify `translateWildcardStructureFanOut` + `getStructuresWithFanOut(..., cacheKey)` invoked with the same key the controller computed. |
| `wildcardAgency_toggleOn_emptyQueries_returnsEmptyBodyNoCacheWrite()` | Translator returns empty list; assert 200 + empty body, `cacheService.putReadyResponse` NOT called. |
| `commaSeparatedAgency_toggleOn_still501()` | Toggle on, agencyId="BIS,IMF"; expect 501. |
| `singleAgency_toggleOn_unaffected()` | Toggle on, agencyId="BIS"; verify single-registry path invoked. |

### Manual Verification

With the toggle off (default):

```bash
curl -sv -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/*/*/*
# Expected: HTTP 501, body mentions "Wildcard and comma-separated agency queries are not supported"
```

POST a config with the toggle on, then re-issue the same request:

```bash
curl -X POST http://localhost:8050/statgpt/sdmx-proxy/api/v0/config \
  -H "Content-Type: application/json" \
  -d @config-with-fanout.json
# config-with-fanout.json: same as current sdmx_registries_config.json + "structureFanOutEnabled": true

curl -sv -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/*/*/*
# Expected: HTTP 200, merged SDMX-JSON structure document containing data structures from each configured registry that supports `datastructure`
```

Sanity check the merged-response cache by re-issuing the same request and
watching the proxy logs:

```bash
# First call: expect "Fan-out cache miss: ..." at DEBUG, followed by per-leg fetches.
curl -s -o /dev/null -w "%{time_total}\n" -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/*/*/*

# Second call (same Accept, same path): expect "Fan-out cache hit: ..." at DEBUG; latency drops sharply.
curl -s -o /dev/null -w "%{time_total}\n" -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/*/*/*
```

### E2E Tests

The existing `StructureWildcardE2ETest` asserts the toggle-off contract.
Two paths forward:

1. **Split into two test classes**:
   `StructureWildcardDisabledE2ETest` keeps the existing 501 assertions
   (no change to setup -- the registry config it pushes has the toggle
   off by omission). `StructureWildcardFanOutE2ETest` pushes the same
   registry config with `structureFanOutEnabled=true` and asserts:
   - HTTP 200
   - non-empty body
   - body parses as the requested format
   - merged result contains at least one structure of the requested type
2. **Single class, two test methods**: switch the toggle in
   `@BeforeEach` per test. Slightly less isolated but cuts duplication.

Recommendation: split into two classes. The shared setup is small (one
JSON file read), and the two test classes make the two distinct contracts
self-documenting. Use the same `bis_3_0_registry_config.json` fixture --
fan-out across one registry still exercises the parallel-execution and
merge code paths, just with `n=1`. Add a second class
`StructureFanOutMultiRegistryE2ETest` that pushes a config with **two**
registries (reuse the existing `bis_3_0_registry_config.json` +
`imf_3_0_registry_config.json` payloads) to exercise the real merge case.

Per CLAUDE.md the user runs E2E tests manually; we do not invoke them.

## SDMX Standard References

The SDMX 3.0 REST spec defines `*` as the wildcard placeholder for "any
maintenance agency" in path parameters; see
`sdmx-rest-2.2.0/v2_2_0/docs/4_2_0_structural_metadata_queries.md` (the
"Parameters used for identifying artefacts" section). Comma-separated
agency IDs are also legal in the spec; we explicitly do not implement them
yet for the routing reason discussed under Non-goals.
