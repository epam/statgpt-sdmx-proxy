# Design 024: `all` keyword alias for `*` on the structure endpoint

**Status:** draft (2026-05-21). Awaiting review.

## Context

The proxy exposes an SDMX 3.0 REST API. In SDMX 3.0 the wildcard
placeholder in the path is `*` (e.g. `/structure/dataflow/*/*/*`); in
SDMX 2.1 the same role was played by `all`. The proxy already handles
the *outbound* direction -- when routing to an SDMX 2.1 registry,
`QueryTranslatorImpl.getVersionSpecificQueryId` rewrites `*` to `all`
before sending. The *inbound* direction is unhandled: a client request
with `all` in any slot is treated as a literal artefact name.

Clients in practice send `all` because:

- They copy-paste from SDMX 2.1 documentation, of which there is far
  more than 3.0 documentation.
- They migrate from a 2.1 client library where `all` was the documented
  wildcard form.
- They are tooling-generated and the tool defaults to 2.1 syntax.

In every case the proxy currently silently returns the wrong thing --
an empty / 404-style result for the literal "all" artefact.

## Problem

`/structure/{type}/all/all/all` and similar mixed forms (e.g.
`/structure/dataflow/BIS/MY_FLOW/all`) currently route as specific-
artefact queries against an artefact literally named "all". The
existing wildcard / fan-out paths only trigger on the exact string
`*`.

In particular:

- The 501 rejection at `QueryTranslatorImpl.translateStructureQuery`
  only checks `"*".equals(agencyId)`, so a request with `agencyId="all"`
  bypasses it and goes through standard single-registry routing.
- The fan-out branch in `SdmxStructure30Controller.getResources` only
  checks `"*".equals(agencyId)`, so `all` never engages fan-out even
  when the toggle is on.

## Non-goals

- **Data and availability endpoints.** SDMX 3.0 data/availability use
  the `c[]` filter model and position-based keys; `all` has no role
  there. Keep scope to the structure endpoint to match SDMX 3.0
  semantics on the surfaces that need it.
- **Case-insensitive matching.** The SDMX 2.1 spec uses the exact
  lowercase form `all`. We match that. `ALL`, `All`, etc. continue to
  be treated as literal artefact names.
- **A separate config flag.** This is a normalization, not a
  feature -- accepting `all` is always-on and does not need a toggle.
  The fan-out toggle (design 023) still gates the wildcard-agency
  behaviour after normalization.
- **Reverse rewrite in responses.** Responses keep whatever IDs the
  upstream registry returned. We do not rewrite `*` back to `all` on
  the way out.

## Current Architecture

Request flow today, ignoring fan-out:

```
SdmxStructure30Controller.getResources(agencyId, resourceId, version, ...)
    [fan-out branch: "*".equals(agencyId) && structureFanOutEnabled]
       -> queryTranslator.translateWildcardStructureFanOut(...)
    [else]
       -> queryTranslator.translateStructureQuery(...)
          [throws 501 iff "*".equals(agencyId) || agencyId.contains(",")]
          -> selectRegistryAndVersion -> single TranslatedStructureQuery
          -> getVersionSpecificQueryId: "*" -> "all" for SDMX 2.1 registries
       -> adapterRouter.getStructures(query)
```

The `"*"` literal appears at three branch points:

1. `SdmxStructure30Controller.getResources` -- fan-out gate.
2. `QueryTranslatorImpl.translateStructureQuery` -- 501 gate.
3. `QueryTranslatorImpl.getVersionSpecificQueryId` -- outbound rewrite
   for SDMX 2.1 registries.

## Solution

The wildcard vocabulary (`*` vs `all`) is SDMX wire-syntax knowledge;
that belongs to the translator. The rule lives in `QueryTranslator`,
and no other component ever sees the literal string `all`.

1. **`QueryTranslator` gains one helper method**: `String normalizePathSlot(String)`.
   It returns `*` if the input is `all`, otherwise the input unchanged.
2. **Inside the two translator entry methods** (`translateStructureQuery`
   and `translateWildcardStructureFanOut`), normalise each path-slot
   parameter at the top. Defensive; the call is idempotent so it costs
   nothing when the caller already normalised.
3. **The structure controller normalises its three path locals once at
   the top of `getResources`** via `queryTranslator.normalizePathSlot(...)`,
   then uses those normalised locals for everything downstream -- the
   fan-out branch check, the cache-key computation, and the translator
   call. Because the locals are already `*` by the time the cache key
   is built, `/structure/.../*/*/*` and `/structure/.../all/all/all`
   share one fan-out cache entry.

The controller learns *that* `all` exists only to the extent that it
calls one translator method named `normalizePathSlot`. It never
inspects the literal string `all`; the existing `"*".equals(agencyId)`
fan-out check stays as-is because the local has already been rewritten.

### Mixed forms work for free

A request like `/structure/dataflow/BIS/MY_FLOW/all` enters the
controller, `normalizePathSlot` rewrites the `version` local from
`all` to `*`, the controller takes the single-registry branch
(`"*".equals("BIS")` is false), and `translateStructureQuery` sees
`agencyId="BIS", resourceId="MY_FLOW", version="*"`. Routing picks
the BIS registry; the existing `getVersionSpecificQueryId` rewrites
`*` back to `all` on the outbound leg when BIS happens to be SDMX
2.1. Net round-trip: `all -> * -> all` for that registry, matching
what the client sent.

## Implementation Plan

### Step 1: Add `normalizePathSlot` to the translator interface

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslator.java`

```java
/**
 * Normalises a structure-endpoint path slot (one of {@code agencyId},
 * {@code resourceId}, {@code version}). The SDMX 2.1 wildcard
 * keyword {@code all} is rewritten to the SDMX 3.0 form {@code *};
 * every other value is returned unchanged.
 * <p>
 * Exact lowercase {@code all} only -- {@code ALL}, {@code All}, etc.
 * are treated as literal artefact IDs (see design 024).
 *
 * @param slot path-slot value as it arrived from the client
 * @return canonical form ({@code *} for wildcard, otherwise the input)
 */
String normalizePathSlot(String slot);
```

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

```java
@Override
public String normalizePathSlot(String slot) {
    return SDMX_21_ALL_WILDCARD.equals(slot) ? SDMX_30_ALL_WILDCARD : slot;
}
```

The constants `SDMX_21_ALL_WILDCARD = "all"` and
`SDMX_30_ALL_WILDCARD = "*"` already exist on the class; no new
literals.

### Step 2: Defensive normalisation inside translator entry methods

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

Apply `normalizePathSlot` at the top of every translator method that
accepts path slots from external callers. The call is idempotent so
it costs nothing when the caller already normalised; this is a
belt-and-suspenders guarantee that no downstream code ever sees
`all`.

```java
@Override
public TranslatedStructureQuery translateStructureQuery(
        String structureType,
        String agencyId,
        String resourceId,
        String version,
        String references,
        String detail,
        String acceptHeader,
        String sourceArtefactUrn
) {
    agencyId = normalizePathSlot(agencyId);
    resourceId = normalizePathSlot(resourceId);
    version = normalizePathSlot(version);

    if ("*".equals(agencyId) || (agencyId != null && agencyId.contains(","))) {
        throw new UnsupportedAgencyWildcardException(/* ... */);
    }
    // ... rest unchanged
}

@Override
public List<TranslatedStructureQuery> translateWildcardStructureFanOut(
        String structureType,
        String resourceId,
        String version,
        String references,
        String detail,
        String acceptHeader
) {
    resourceId = normalizePathSlot(resourceId);
    version = normalizePathSlot(version);
    // ... rest unchanged
}
```

`translateStructureQueryForAgencySchemaDiscovery` does not take
client-supplied path slots (it hard-codes `*`), so no change there.
Data and availability translator methods are out of scope per
Non-goals.

### Step 3: Controller normalises locals up front

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/SdmxStructure30Controller.java`

At the top of `getResources`, normalise the three path locals via the
translator helper. Everything that follows -- fan-out branch check,
cache-key computation, translator call -- uses the canonical values.

```java
@Override
public ResponseEntity<StreamingResponseBody> getResources(
        @PathVariable("structureType") String structureType,
        @PathVariable("agencyId") String agencyId,
        @PathVariable("resourceId") String resourceId,
        @PathVariable("version") String version,
        // ...
) {
    agencyId = queryTranslator.normalizePathSlot(agencyId);
    resourceId = queryTranslator.normalizePathSlot(resourceId);
    version = queryTranslator.normalizePathSlot(version);

    logRequestUrl(accept, STRUCTURE);
    // ... existing logic unchanged; the locals are now guaranteed canonical
}
```

**Key points:**

- The existing fan-out branch `"*".equals(agencyId) && config.isStructureFanOutEnabled()`
  stays as-is -- by the time it runs, `agencyId` is either `*` or a
  literal agency name, never `all`.
- `fanOutResponse` builds the cache key from the now-canonical
  `resourceId` and `version` locals, so `/structure/.../*/*/*` and
  `/structure/.../all/all/all` produce the **same** cache key.
- `logRequestUrl` reads from `HttpServletRequest.getRequestURL()`, not
  from our locals, so the log line preserves the client's original
  form for debugging.

### Step 4: No other changes

The 501 gate (`"*".equals(agencyId) || agencyId.contains(",")`),
`getVersionSpecificQueryId`, and `CacheKeyGenerator.generateFanOutResponseKey`
all see canonical inputs and continue to work without modification.

## No Changes Required

These files need **no modification**:

- **`AgencyRoutingService`** -- only invoked for non-wildcard agency
  IDs.
- **`CacheKeyGenerator`** -- key components are passed through as
  strings; the normalisation already happened upstream.
- **`getVersionSpecificQueryId`** -- already converts `*` to `all` on
  the outbound leg for SDMX 2.1 registries. Untouched.
- **Data / availability controllers** -- out of scope (see Non-goals).

## Files Affected

| File | Change Type | Description |
|------|-------------|-------------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslator.java` | Modified | Add `normalizePathSlot(String)` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java` | Modified | Implement `normalizePathSlot`; normalise at entry of `translateStructureQuery` and `translateWildcardStructureFanOut` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/SdmxStructure30Controller.java` | Modified | Normalise the three path locals via `queryTranslator.normalizePathSlot(...)` at the top of `getResources` |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java` | Modified | Tests for `normalizePathSlot` and for the defensive normalisation in each translator entry method |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/controller/SdmxStructure30ControllerTest.java` | Modified | Tests that `agencyId="all"` engages the fan-out branch when the toggle is on, and that `/all/all/all` and `/*/*/*` produce the same cache key |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/StructureFanOutE2ETest.java` | Modified | One parameterised case using `all` instead of `*` to confirm the alias works end-to-end |

## Edge Cases

1. **`all` in only one slot.** `/structure/dataflow/BIS/MY_FLOW/all`
   normalises to `BIS / MY_FLOW / *`. Routes to the BIS registry, asks
   for "any version of MY_FLOW", and the outbound rewrite re-emits
   `all` to BIS if BIS is 2.1.
2. **`all` agency with fan-out off.** Normalises to `*`; existing 501
   gate fires. Same response as `/structure/.../*/*/*` with toggle
   off.
3. **`all` agency with fan-out on.** Normalises to `*`; existing
   fan-out branch engages.
4. **Case variants (`ALL`, `All`).** Not normalised. Treated as
   literal artefact names. Documented in Non-goals; can be revisited
   if real clients send other casings.
5. **Mixed `all,BIS` comma-separated lists.** Comma-separated still
   rejected by the existing 501 gate after normalisation (the
   normaliser only matches the exact string `all`, not any token
   inside a comma list).
6. **Cache aliasing.** `/structure/dataflow/*/*/*` and
   `/structure/dataflow/all/all/all` share **one** fan-out cache
   entry: the controller's `normalizePathSlot` calls run before the
   key is computed, so both forms produce identical key inputs. Same
   story for the per-registry parsed-structures cache (it is keyed
   off the post-translation `TranslatedStructureQuery`, where the
   defensive normalisation has already happened).

## Verification

### Unit Tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java`

| Test Method | Description |
|-------------|-------------|
| `normalizePathSlot_rewritesAllToStar()` | Returns `*` for input `all`. |
| `normalizePathSlot_passesThroughStar()` | Returns `*` for input `*`. |
| `normalizePathSlot_passesThroughLiteral()` | Returns `BIS` for input `BIS`. |
| `normalizePathSlot_caseVariantsTreatedAsLiterals()` | Returns `ALL` / `All` unchanged. |
| `normalizePathSlot_passesThroughCommaList()` | Returns `all,IMF` unchanged (substring match is not done; the 501 gate handles comma lists). |
| `translateStructureQuery_allInAgencySlot_throws501LikeWildcard()` | Calling with `agencyId="all"` hits the same 501 gate as `agencyId="*"` (the translator normalises before checking). |
| `translateStructureQuery_allInResourceOrVersion_carriesWildcardInStructure()` | `agencyId="BIS", resourceId="all", version="all"` -- the resulting `TranslatedStructureQuery.structure` carries `*` in those slots. |
| `translateWildcardStructureFanOut_allInResourceAndVersionNormalised()` | Calling fan-out with `resourceId="all", version="all"` produces per-leg queries with `*` in both slots. |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/controller/SdmxStructure30ControllerTest.java`

| Test Method | Description |
|-------------|-------------|
| `allAgency_toggleOn_engagesFanOut()` | Wire the translator mock so `normalizePathSlot("all")` returns `*`; assert the controller invokes `translateWildcardStructureFanOut` (not single-agency translate). |
| `allAgency_toggleOff_routesToSingleAgencyTranslatorWhichThrows501()` | `agencyId="all"` with toggle off behaves like `agencyId="*"` with toggle off. |
| `allInResourceAndVersion_toggleOn_cacheKeyMatchesStarForm()` | Capture the cache key passed to `cacheService.getReadyResponse` for `/all/all/all` and `/*/*/*`; assert they are equal. |

### Manual Verification

With fan-out enabled:

```bash
# Canonical 3.0 form
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/dataflow/*/*/*

# 2.1 alias form -- expect identical response
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/dataflow/all/all/all

# Mixed form -- single registry, "any version of MY_FLOW"
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/dataflow/BIS/MY_FLOW/all
```

### E2E Tests

Extend `StructureFanOutE2ETest` (toggle-on suite from design 023) with
one parameterised case that uses `all/all/all` instead of `*/*/*`,
asserting the same HTTP 200 + non-empty body contract. One alias case
is sufficient -- the unit tests above cover the slot matrix; the E2E
case only needs to prove the normaliser runs in the deployed jar.

## SDMX Standard References

- SDMX 2.1 REST: `all` is the documented wildcard for `agencyId` /
  `resourceId` / `version` in path queries.
- SDMX 3.0 REST: `*` replaces `all`; the spec does not require
  servers to keep accepting `all`, but doing so is a low-cost
  compatibility courtesy.
