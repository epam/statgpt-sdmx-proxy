# Design: Merge All-Wildcard Key for Restrictive Registries (Issue #41)

## Context

Issue #41 reports that the BIS SDMX v2 registry (https://stats.bis.org/api/v2)
returns no data when a data query key is composed entirely of wildcards,
one per dimension (e.g. `*.*.*.*` for a 4-dimension dataflow). BIS accepts
only a single `*` as the "match all" form. IMF, in contrast, accepts either.

Reproducible example (BIS, `WS_EER`, 4 dimensions):

- Works: `GET .../data/dataflow/BIS/WS_EER/1.0/*?dimensionAtObservation=TIME_PERIOD&firstNObservations=1`
- Works: `GET .../data/dataflow/BIS/WS_EER/1.0/M.*.*.*?...`
- Fails (empty response): `GET .../data/dataflow/BIS/WS_EER/1.0/*.*.*.*?...`

The proxy's role is to insulate clients from per-registry quirks. The
existing machinery already normalizes keys in the opposite direction via
`replaceEmptyDimensionsWithWildcard` (commit 163aa5e / issue #28), which
expands `"..L_T.P_F3"` to `"*.*.L_T.P_F3"` for IMF. The BIS fix is the
complementary collapse: when every dimension position is `*`, shrink the
whole key to a single `*`.

Per product decision, the flag is applied to both the data and
availability endpoints so that BIS availability queries carrying an
expanded all-wildcard key also get normalized, even though the reported
issue mentions only data.

## Problem

The failing request:

```
GET https://stats.bis.org/api/v2/data/dataflow/BIS/WS_EER/1.0/*.*.*.*?dimensionAtObservation=TIME_PERIOD&firstNObservations=1
```

returns an empty payload from BIS. The proxy passes the key through
verbatim, so any client that expands `*` to per-dimension wildcards (a
reasonable SDMX-REST interpretation) silently gets no data against BIS.

Root cause: the registry-specific key format requirement is not encoded in
the proxy's routing/translation layer. `QueryTranslatorImpl` treats the key
as opaque after the existing empty-dimension replacement step.

## Current Architecture

Data-query flow (relevant section):

```
translateDataQuery(...)
  -> filters present? -> SDMX 2.1: mergeFilters; SDMX 3.0: passthrough
  -> dataEndpointConfig.replaceEmptyDimensionsWithWildcard -> split on '.', replace "" with "*"
  -> build TranslatedDataQuery
```

Availability-query flow (relevant section):

```
translateAvailabilityQuery(...)
  -> processFilters(...) -> validated + (for 2.1) merged into key
  -> build TranslatedAvailabilityQuery
```

Both flows terminate with a `processedKey` that is sent onward verbatim.
The fix is a new normalization step inserted *after* existing key processing
so that `replaceEmptyDimensionsWithWildcard` + `mergeAllWildcardKey` compose
cleanly (empty positions first become `*`, then an all-`*` result collapses
to a single `*`).

## Solution

Add a boolean flag `mergeAllWildcardKey` to both
`DataEndpointConfiguration` and `AvailabilityEndpointConfiguration`. When
enabled, `QueryTranslatorImpl` collapses any key whose every non-empty
dot-separated part equals `*` down to a single `*`.

Rationale:

- Mirrors the existing `replaceEmptyDimensionsWithWildcard` pattern: a
  per-endpoint boolean on the endpoint config, consumed in
  `QueryTranslatorImpl` as a static helper.
- Keeping the helper static makes it unit-testable without booting Spring,
  matching the existing style for `replaceEmptyDimensionsWithWildcard`
  (`QueryTranslatorImpl.java:638-649`).
- Applying the collapse *after* empty-dimension replacement means an input
  like `".*.*.*"` (with both flags on) normalises to `"*"` in one
  translator pass -- no extra ordering work needed elsewhere.

Non-goals:

- No change to SDMX 2.1 `mergeFilters` behavior.
- No change to clients that send `*` already (input unchanged, output
  unchanged).
- No new routing decisions, no new fixtures.

## Implementation Plan

### Step 1: Add the flag to `DataEndpointConfiguration`

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/DataEndpointConfiguration.java`

Add a single boolean field next to the existing flag. The `@Data`
annotation supplies the accessor, matching the pattern in the same file.

```java
package com.epam.sdmxproxy.configuration.data;

import lombok.Data;
import lombok.EqualsAndHashCode;

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
     * When true, a key whose every dimension position is '*' is collapsed to a single '*'.
     * Required for registries (e.g., BIS) that accept only one '*' as the match-all form
     * and return no data for a per-dimension expansion.
     * For example, "*.*.*.*" becomes "*".
     */
    private boolean mergeAllWildcardKey;

}
```

### Step 2: Add the same flag to `AvailabilityEndpointConfiguration`

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/AvailabilityEndpointConfiguration.java`

Add `mergeAllWildcardKey` alongside `unwrapStarComponentId` and
`unwrapFilterParameters`:

```java
/**
 * When true, a key whose every dimension position is '*' is collapsed to a single '*'.
 * Required for registries (e.g., BIS) that accept only one '*' as the match-all form
 * on availability queries.
 * For example, "*.*.*.*" becomes "*".
 */
private boolean mergeAllWildcardKey;
```

### Step 3: Apply the flag in `QueryTranslatorImpl`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

**3a. Data path** -- extend the existing block around line 410-413 so the
collapse runs right after the empty-dimension replacement:

```java
DataEndpointConfiguration dataEndpointConfig = versionConfig.getDataEndpointConfig();
if (dataEndpointConfig != null && dataEndpointConfig.isReplaceEmptyDimensionsWithWildcard()) {
    processedKey = replaceEmptyDimensionsWithWildcard(processedKey);
}
if (dataEndpointConfig != null && dataEndpointConfig.isMergeAllWildcardKey()) {
    processedKey = mergeAllWildcardKey(processedKey);
}
```

**3b. Availability path** -- in `translateAvailabilityQuery` (around line
250-258), add the same check right after `processFilters(...)` returns:

```java
String processedKey = processFilters(
        filters,
        selectedRegistry.getVersionConfiguration(),
        sdmxBeans,
        key,
        agencyID,
        resourceID,
        version
);

AvailabilityEndpointConfiguration availabilityEndpointConfig =
        selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig();
if (availabilityEndpointConfig != null && availabilityEndpointConfig.isMergeAllWildcardKey()) {
    processedKey = mergeAllWildcardKey(processedKey);
}
```

**3c. Add the static helper** -- place next to
`replaceEmptyDimensionsWithWildcard` at the bottom of the class
(`QueryTranslatorImpl.java:638-649`):

```java
/**
 * Collapses a key whose every dimension position is '*' to a single '*'.
 * Leaves all other keys (including single '*', null, empty, and keys with
 * any literal value) unchanged.
 * Examples: "*.*" -> "*", "*.*.*.*" -> "*", "M.*.*" -> "M.*.*".
 */
static String mergeAllWildcardKey(String key) {
    if (key == null || key.isEmpty()) {
        return key;
    }
    String[] parts = key.split("\\.", -1);
    for (String part : parts) {
        if (!"*".equals(part)) {
            return key;
        }
    }
    return "*";
}
```

Note on import: `AvailabilityEndpointConfiguration` must be imported --
verify the file's existing imports already contain it (it is used
elsewhere in this class for the availability return-format logic around
line 575, so the import already exists).

### Step 4: Enable the flag for BIS in the main registry config

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`

Under the BIS `SDMX_3_0` block, add `mergeAllWildcardKey: true` to both
`dataEndpointConfig` and `availabilityEndpointConfig`:

```json
"dataEndpointConfig": {
  "url": "https://stats.bis.org/api/v2/data/",
  "supportedFormats": ["JSON_1_0_0", "CSV_DATA_2_0_0"],
  "defaultFormat": "JSON_1_0_0",
  "bypassEnabled": false,
  "mergeAllWildcardKey": true
},
"availabilityEndpointConfig": {
  "url": "https://stats.bis.org/api/v2/availability/",
  "supportedFormats": ["JSON_STRUCTURE_2_0_0"],
  "defaultFormat": "JSON_STRUCTURE_2_0_0",
  "bypassEnabled": false,
  "availabilityEnabled": true,
  "unwrapStarComponentId": true,
  "unwrapFilterParameters": false,
  "mergeAllWildcardKey": true
}
```

Do **not** enable on the IMF block -- IMF accepts the expanded form.

### Step 5: Enable the flag for BIS in the E2E registry config

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_registry_config.json`

Mirror the main config -- set `mergeAllWildcardKey: true` on both
`dataEndpointConfig` and `availabilityEndpointConfig` in the BIS SDMX 3.0
block.

### Step 6: Extend the BIS E2E test config to exercise the path

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_test_config.json`

Add a per-dimension wildcard key to `WS_EER` (4 dimensions) in
`dataTestSuitConfiguration.dataflowConfigs`:

```json
{
  "urn": "BIS:WS_EER(1.0)",
  "keys": [
    "*",
    "*.*.*.*"
  ],
  "filters": {}
}
```

Optionally add `"*.*.*.*"` as a companion to an existing availability
dataflow (e.g. `WS_CBPOL`, 3 dimensions: `"*.*.*"`) to cover the
availability path end-to-end.

## No Changes Required

These files need **no modification**:

- **`AdapterRouterImpl.java`** -- the router works on `TranslatedDataQuery`
  / `TranslatedAvailabilityQuery` objects; the key is opaque to it.
- **`GenericRegistryAdapterImpl.java`** -- same; receives the already-
  normalized key.
- **IMF registry configs** (main and E2E) -- IMF accepts `*.*.*.*`, so
  leaving `mergeAllWildcardKey` unset (false) preserves current behavior.
- **`FilterValidator`, `FilterTranslator`** -- the collapse is a pure
  string normalization on an already-validated key.

## Files Affected

| File                                                                                                           | Change Type | Description                                             |
|----------------------------------------------------------------------------------------------------------------|-------------|---------------------------------------------------------|
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/DataEndpointConfiguration.java`         | Modified    | Add `mergeAllWildcardKey` boolean                       |
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/AvailabilityEndpointConfiguration.java` | Modified    | Add `mergeAllWildcardKey` boolean                       |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`                     | Modified    | New static helper + two call sites (data, availability) |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`                                             | Modified    | Enable flag for BIS data + availability                 |
| `sdmx-proxy-e2e/.../registry/bis/3_0/bis_3_0_registry_config.json`                                             | Modified    | Enable flag for BIS E2E                                 |
| `sdmx-proxy-e2e/.../registry/bis/3_0/bis_3_0_test_config.json`                                                 | Modified    | Add `"*.*.*.*"` key to exercise the path                |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java`                 | Modified    | Unit tests (static helper + integration)                |

## Edge Cases

1. **`"*"` (single wildcard)** -- `parts = ["*"]`, every part is `*`,
   result is `"*"`. No observable change.
2. **`"*.*"` / `"*.*.*.*"` (all-wildcard expanded)** -- collapses to `"*"`.
3. **`"M.*.*.*"` (partial filter)** -- first part is not `*`, returned
   unchanged.
4. **`"*.M"`** -- second part is not `*`, returned unchanged.
5. **`""` (empty string)** -- early return, unchanged. Matches
   `replaceEmptyDimensionsWithWildcard` behavior.
6. **`null`** -- early return, unchanged.
7. **Composed with `replaceEmptyDimensionsWithWildcard`** -- input
   `".*.*.*"` with both flags on: empty-dim step yields `"*.*.*.*"`, merge
   step yields `"*"`. Correct.
8. **Composed with `mergeFilters` for SDMX 2.1** -- filter-merge produces
   a concrete key; if that key happens to be all `*`, collapse still
   applies. Consistent behavior for any 2.1 registry that might adopt the
   flag in the future.
9. **Flag disabled** -- no-op. All existing behavior preserved.

## Verification

### Unit Tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java`

Mirror the tests for `replaceEmptyDimensionsWithWildcard` already present
in this file (section `replaceEmptyDimensionsWithWildcard -- static helper
tests` near the end).

**Static helper tests**

| Test Method                                  | Description                                              |
|----------------------------------------------|----------------------------------------------------------|
| `testMergeAllWildcardKey_singleStar()`       | `"*"` -> `"*"` (unchanged)                               |
| `testMergeAllWildcardKey_twoStars()`         | `"*.*"` -> `"*"`                                         |
| `testMergeAllWildcardKey_fourStars()`        | `"*.*.*.*"` -> `"*"`                                     |
| `testMergeAllWildcardKey_partialFilter()`    | `"M.*.*.*"` -> `"M.*.*.*"` (unchanged)                   |
| `testMergeAllWildcardKey_starThenLiteral()`  | `"*.M"` -> `"*.M"` (unchanged)                           |
| `testMergeAllWildcardKey_null()`             | `null` -> `null`                                         |
| `testMergeAllWildcardKey_empty()`            | `""` -> `""`                                             |
| `testMergeAllWildcardKey_emptyPosition()`    | `"*..*"` -> `"*..*"` (contains empty part, unchanged -- this case is handled by `replaceEmptyDimensionsWithWildcard`) |

**Integration tests (via `translateDataQuery`)**

| Test Method                                                          | Description                                        |
|----------------------------------------------------------------------|----------------------------------------------------|
| `testTranslateDataQuery_mergeAllWildcardKey_flagEnabled()`           | BIS-style config, key `"*.*.*.*"` -> produced `"*"` |
| `testTranslateDataQuery_mergeAllWildcardKey_flagDisabled()`          | Default config, key `"*.*.*.*"` -> unchanged        |
| `testTranslateDataQuery_mergeAllWildcardKey_partialUnchanged()`      | Flag on, key `"M.*.*.*"` -> unchanged               |
| `testTranslateDataQuery_mergeAllWildcardKey_composesWithEmptyDims()` | Both flags on, key `".*.*.*"` -> `"*"`              |

**Integration tests (via `translateAvailabilityQuery`)**

| Test Method                                                         | Description                                  |
|---------------------------------------------------------------------|----------------------------------------------|
| `testTranslateAvailabilityQuery_mergeAllWildcardKey_flagEnabled()`  | Flag on, key `"*.*.*"` -> `"*"`              |
| `testTranslateAvailabilityQuery_mergeAllWildcardKey_flagDisabled()` | Flag off, key `"*.*.*"` -> unchanged         |

Use the existing test helpers (`createRegistryWithSingleVersion`,
`agencyRoutingService` mocking) already established at the bottom of
`QueryTranslatorImplTest.java` -- see the `replaceEmptyDimensionsWithWildcard`
integration tests as a direct template.

### Manual Verification

```bash
./gradlew :sdmx-proxy:bootRun
```

Then, against the running proxy (port 8050):

```bash
# Expected: 200 with data (same as single '*')
curl -i -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "http://localhost:8050/api/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/*.*.*.*?dimensionAtObservation=TIME_PERIOD&firstNObservations=1"

# Control: already-working single wildcard
curl -i -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "http://localhost:8050/api/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/*?dimensionAtObservation=TIME_PERIOD&firstNObservations=1"

# Control: partial filter (must remain unchanged end-to-end)
curl -i -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "http://localhost:8050/api/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/M.*.*.*?dimensionAtObservation=TIME_PERIOD&firstNObservations=1"
```

With `FEIGN_LOG_LEVEL=BASIC`, the first request should show an outbound
call to BIS with `.../WS_EER/1.0/*?...` (collapsed), not `*.*.*.*`.

### E2E Tests

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
```

The added `"*.*.*.*"` key for `WS_EER` (and optional availability key)
exercises the new collapse path. Existing BIS cases that use `"*"` or
partial keys continue to pass -- the flag is a pure no-op for those
inputs.

## SDMX Standard References

- SDMX-REST 2.2.0 key grammar: `sdmx-rest-2.2.0/doc/2_1_structural_queries.md`
  (see the key wildcard semantics). The spec permits either form; this
  fix normalises to the one BIS accepts without violating the standard for
  clients.
- Related prior fix: commit 163aa5e, issue #28 -- adds the inverse
  `replaceEmptyDimensionsWithWildcard` flag for IMF on the data endpoint.
