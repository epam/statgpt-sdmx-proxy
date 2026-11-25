# Design: Availability Filter Parameter Unwrapping

## Context

The SDMX 3.0 REST standard defines filter parameters for availability queries using the `c[DIMENSION]=value` format
(URL-encoded as `c%5BDIMENSION%5D=value`). The proxy correctly implements this: filters arrive from the client, are
extracted by `QueryTranslatorImpl.extractFilters()`, and re-wrapped by `GenericRegistryAdapterImpl.wrapIntoC()` before
being passed to the SDMX 3.0 Feign client via `@QueryMap`.

However, not all SDMX 3.0 registries support this standard parameter format.

## Problem

BIS registry (`stats.bis.org`) does not recognize `c[DIMENSION]=value` query parameters on the availability endpoint.
When the proxy sends:

```
GET /api/v2/availability/dataflow/BIS/WS_LBS_D_PUB/1.0/%2A/FREQ,...?c%5BL_CP_COUNTRY%5D=US&c%5BFREQ%5D=Q
```

BIS returns a response with empty cube regions -- the filters are silently ignored.

When the same request is made with raw dimension names as query parameters (without the `c[]` wrapper):

```
GET /api/v2/availability/dataflow/BIS/WS_LBS_D_PUB/1.0/%2A/FREQ,...?L_CP_COUNTRY=US&FREQ=Q
```

BIS returns correct, non-empty cube regions.

### Code path

1. Client POSTs to `/api/v0/sdmx/3.0/availability/dataflow/BIS/WS_LBS_D_PUB/1.0` with filters
2. `QueryTranslatorImpl.extractFilters()` extracts `c[FREQ]=Q` -> `{FREQ: [Q]}`
3. `GenericRegistryAdapterImpl.getAvailability30()` (line 250) calls `wrapIntoC()` (line 262)
4. `wrapIntoC()` re-wraps: `{FREQ: [Q]}` -> `{c[FREQ]: [Q]}`
5. Feign client URL-encodes brackets: `c%5BFREQ%5D=Q`
6. BIS ignores the `c[]`-wrapped parameters -> empty cube regions

## Solution

Add a boolean configuration flag `unwrapFilterParameters` to `AvailabilityEndpointConfiguration`. When enabled, the
proxy passes filter dimension names directly as query parameters (e.g., `FREQ=Q`) instead of wrapping them in `c[]`
format (e.g., `c[FREQ]=Q`).

This follows the established pattern of `unwrapStarComponentId` -- a per-registry boolean flag on the availability
endpoint config that works around a specific registry quirk.

## Changes

### Step 1: Add `unwrapFilterParameters` to `AvailabilityEndpointConfiguration`

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/AvailabilityEndpointConfiguration.java`

Add a new boolean field after `unwrapStarComponentId`:

```java
/**
 * When true, availability filter parameters are sent as raw dimension query params
 * (e.g., FREQ=Q) instead of the SDMX 3.0 c[] wrapper (e.g., c[FREQ]=Q).
 * Required for registries that do not support the c[] format on availability queries.
 */
private boolean unwrapFilterParameters;
```

Lombok `@Data` generates `isUnwrapFilterParameters()` automatically.

### Step 2: Conditional filter wrapping in `GenericRegistryAdapterImpl`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java`

In `getAvailability30()` (line 262), replace:

```java
wrapIntoC(query.getFilters()),
```

with:

```java
resolveAvailabilityFilters(query.getFilters(), selectedRegistry),
```

Add a new private method:

```java
private MultiValueMap<String, String> resolveAvailabilityFilters(MultiValueMap<String, String> filters,
                                                                  RegistrySelectionResult selectedRegistry) {
    AvailabilityEndpointConfiguration availabilityConfig =
            selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig();
    if (availabilityConfig != null && availabilityConfig.isUnwrapFilterParameters()) {
        return filters != null ? filters : new LinkedMultiValueMap<>();
    }
    return wrapIntoC(filters);
}
```

**New imports:**

```java
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
```

### Step 3: Enable the flag for BIS in registry configs

**File:** `sdmx-proxy/src/main/resources/sdmx_registries_config.json`

In the BIS `availabilityEndpointConfig` section, add after `"unwrapStarComponentId": true`:

```json
"unwrapFilterParameters": true
```

**File:** `sdmx-proxy-e2e/src/test/resources/.../bis/3_0/bis_3_0_registry_config.json`

Same change -- add `"unwrapFilterParameters": true` to the BIS availability config (also add the currently missing
`"unwrapStarComponentId": true` to align with the main config).

### Step 4: Add E2E availability test case with filters

**File:** `sdmx-proxy-e2e/src/test/resources/.../bis/3_0/bis_3_0_test_config.json`

Add a new availability dataflow entry with non-empty filters to verify the fix end-to-end:

```json
{
  "urn": "BIS:WS_CBPOL(1.0)",
  "keys": ["*"],
  "filters": {
    "FREQ": "M"
  }
}
```

This uses the same `WS_CBPOL` dataflow already tested without filters, now with a `FREQ=M` filter.

### Step 5: Unit test for `resolveAvailabilityFilters`

Add a unit test covering:

1. `unwrapFilterParameters = false` (default) -- filters wrapped with `c[]`
2. `unwrapFilterParameters = true` -- filters passed as-is
3. `unwrapFilterParameters = true`, null filters -- returns empty map
4. `unwrapFilterParameters = true`, empty filters -- returns empty map
5. `availabilityConfig = null` -- falls back to `wrapIntoC`

## Edge Cases

1. **Null/empty filters**: `wrapIntoC()` already returns empty `LinkedMultiValueMap` for null/empty input.
   `resolveAvailabilityFilters` preserves this behavior in both paths.
2. **SDMX 2.1 registries**: Not affected. `getAvailability21()` does not use `wrapIntoC()` -- it translates filters
   into key dot-notation via `FilterTranslatorImpl.mergeFiltersIntoKey()`.
3. **Data endpoint**: `getData30()` also calls `wrapIntoC()`. If BIS data queries with filters also fail, a similar flag
   can be added to `DataEndpointConfiguration`. Scoped to availability only for now since the reported issue is
   availability-specific.
4. **Other registries**: `unwrapFilterParameters` defaults to `false` (Java boolean default), so no change to existing
   behavior for IMF, Eurostat, or any other registry.

## Verification

1. `./gradlew clean build -x test` -- build succeeds
2. `./gradlew :sdmx-proxy:test` -- unit tests pass (including new test)
3. Manual test with BIS:
   ```
   POST /api/v0/sdmx/3.0/availability/dataflow/BIS/WS_CBPOL/1.0
   Body: { "filters": [{ "componentCode": "FREQ", "operator": "eq", "value": "M" }] }
   ```
   Verify non-empty cube regions in response.
4. `./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"` -- E2E tests pass (including new filtered
   availability case)

## Files Modified (Summary)

| File                                            | Change                                             |
|-------------------------------------------------|----------------------------------------------------|
| `AvailabilityEndpointConfiguration.java`        | Add `unwrapFilterParameters` boolean field          |
| `GenericRegistryAdapterImpl.java`               | Add `resolveAvailabilityFilters()` method, use it   |
| `sdmx_registries_config.json`                   | Add `"unwrapFilterParameters": true` for BIS        |
| `bis_3_0_registry_config.json` (E2E)            | Add `"unwrapFilterParameters": true` for BIS        |
| `bis_3_0_test_config.json` (E2E)                | Add availability entry with filters                 |
| `GenericRegistryAdapterImplTest.java` (new/ext) | Unit tests for conditional filter wrapping          |
