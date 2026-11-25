# Design: Reject repeated c[X] query parameters with HTTP 400

## Context

Bug #38: when a client sends two `c[TIME_PERIOD]` parameters against a data endpoint (e.g. one `ge:` and one `le:`),
only the bound that arrives first in the query string is reflected in the response. Reversing the parameter order
produces the mirror symptom. No error is returned to the client.

Example failing request against BIS:

```
GET /api/v0/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/D.N.B.DE
    ?c[TIME_PERIOD]=ge:2025-01-01
    &c[TIME_PERIOD]=le:2025-01-31
    &detail=full
```

Response returns data from 2025-01-01 onward with no upper bound.

### Root cause

The SDMX 3.0 REST spec (`sdmx-rest-2.2.0/doc/data.md:20`) states:

> This parameter may be used multiple times (e.g. `c[FREQ]=A,M&c[CONF_STATUS]=F`), **but only once per Component**.

The spec-compliant way to express AND within a single Component is `c[X]=ge:A+le:B` (single parameter,
`+`-separated values). OR uses `,`.

The failing request violates the spec by repeating `c[TIME_PERIOD]`. The proxy does not drop the duplicate — it
forwards both occurrences to the registry unchanged via `GenericRegistryAdapterImpl.wrapIntoC` + Feign `@QueryMap`,
producing an outbound URL like `?c[TIME_PERIOD]=ge:...&c[TIME_PERIOD]=le:...`. BIS, which honors the spec's
"only once per Component" rule, applies just the first occurrence of `c[TIME_PERIOD]` and ignores the rest; the
proxy then returns that partial result to the client.

### Decision

**Reject these requests with HTTP 400** at the proxy, so the registry never sees the spec-violating shape and the
client gets an explicit, actionable error instead of a silently partial response. Point the client at the
spec-compliant forms (`+` for AND, `,` for OR). No normalization, no tolerance — strict rejection.

This is the simplest fix, makes the failure mode visible, and avoids guessing the client's intent (AND vs OR) when
the spec forbids the input shape in the first place.

### Out of scope

Related latent issue: for SDMX 2.1 backends, non-time components with multiple raw values are silently truncated
to the first value by `FilterTranslatorImpl.mergeFiltersIntoKey:45` (`values.get(0)`). The new validation below
also catches this at the front door, so no separate fix is needed — both scenarios produce the same 400 response.

## Solution

Add a version-independent structural check in `QueryTranslatorImpl.extractFilters`: when any `c[X]` key in the
incoming request has more than one raw value, throw `FilterValidationException` with a message pointing the client
at the correct syntax. The existing `GlobalExceptionHandler:80-85` converts the exception into an HTTP 400 with
the standard `ErrorResponse` body — no new exception class or handler required.

`extractFilters` is called from both `translateDataQuery` (line 383) and `translateAvailabilityQuery` (line 248),
so the check covers both endpoints in one place.

### Why `extractFilters` and not `FilterValidatorImpl`?

- `extractFilters` is the single unwrap point where `c[X]` param names become Component IDs. Enforcing "at most
  one value per X" belongs at that structural boundary.
- `FilterValidatorImpl` is currently gated on SDMX 2.1 (line 43) and performs semantic validation against the DSD
  (dimension membership, operator support). A duplicate-key check is structural, not semantic, and must apply to
  all SDMX versions.
- Keeping `FilterValidatorImpl` untouched avoids perturbing its 2.1-specific behavior and the path-specific call
  sites that depend on it.

## Changes

### Step 1 — Reject duplicates in `extractFilters`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

Modify `extractFilters` (lines 304-320). Add a size check before accepting the entry:

```java
private MultiValueMap<String, String> extractFilters(MultiValueMap<String, String> c) {
    if (c == null || c.isEmpty()) {
        return c;
    }

    MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();

    for (Map.Entry<String, List<String>> entry : c.entrySet()) {
        String paramName = entry.getKey();
        if (paramName.startsWith("c[") && paramName.endsWith("]")) {
            List<String> values = entry.getValue();
            if (values != null && values.size() > 1) {
                throw new FilterValidationException(String.format(
                        "Query parameter '%s' appears %d times but may only be used once per Component. " +
                        "Combine values in a single parameter: use '+' for AND (e.g. '%s=ge:A+le:B') " +
                        "or ',' for OR (e.g. '%s=A,B').",
                        paramName, values.size(), paramName, paramName
                ));
            }
            String componentId = paramName.substring(2, paramName.length() - 1);
            filters.put(componentId, values);
        }
    }

    return filters.isEmpty() ? null : filters;
}
```

Notes:
- `FilterValidationException` is already imported in this file (line 17).
- The check runs before `filters.put`, so no partial state is produced on failure.
- Non-`c[...]` entries (other query params Spring binds into the same `MultiValueMap`) are untouched.

### Step 2 — Unit tests

**File**: `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java`

The existing test `testTranslateDataQuery_21_TimeFilterMultipleParams_IMF_WEO_mapsToStartPeriodEndPeriod` (line 607)
currently asserts that two `c[TIME_PERIOD]` values map to `startPeriod`/`endPeriod` — **this test must be updated**
to assert that the call now throws `FilterValidationException` instead.

New/updated tests:

1. `testTranslateDataQuery_30_DuplicateTimePeriodFilter_throwsFilterValidationException` — reproduces the bug
   scenario against a 3.0 registry (BIS) and asserts `FilterValidationException` with the helper message.
2. `testTranslateDataQuery_21_DuplicateTimePeriodFilter_throwsFilterValidationException` — **replaces** the
   existing test at line 607; same inputs, new expectation.
3. `testTranslateDataQuery_30_DuplicateNonTimeFilter_throwsFilterValidationException` — asserts uniform behavior
   for a non-time component (e.g. `c[FREQ]=A&c[FREQ]=M`).
4. `testTranslateDataQuery_30_SinglePlusJoinedTimeFilter_accepted` — regression: `c[TIME_PERIOD]=ge:A+le:B`
   (single param, `+`-joined) still succeeds. Mirrors the existing
   `testTranslateDataQuery_30_TimeFilterDoesNotSetStartPeriodEndPeriod` pattern at line 582.
5. `testTranslateAvailabilityQuery_30_DuplicateFilter_throwsFilterValidationException` — confirms the check also
   fires on the availability path.

### Step 3 — E2E regression check (optional, deferred)

No E2E change required for correctness. If desired, `bis_3_0_test_config.json` could grow a single negative case
asserting HTTP 400 on a duplicate `c[TIME_PERIOD]` request — but the existing E2E harness focuses on happy-path
200s, so adding negative cases is out of scope for this fix.

## No changes required

- `GlobalExceptionHandler.java` — already maps `FilterValidationException` to HTTP 400 (line 80-85).
- `FilterValidationException.java` — no new exception type needed.
- `FilterValidatorImpl.java` — existing semantic validator is untouched.
- `GenericRegistryAdapterImpl.wrapIntoC` — never receives multi-value filters now, so no change.
- Controller signatures (`DataQuery30Controller`, `AvailabilityQuery30Controller`) — bind behavior unchanged.
- Registry configs (`sdmx_registries_config.json`) — no new flags.

## Edge cases

| Input                                              | Behavior                                                        |
|----------------------------------------------------|-----------------------------------------------------------------|
| `c[TIME_PERIOD]=ge:A&c[TIME_PERIOD]=le:B`          | 400 with actionable message                                     |
| `c[TIME_PERIOD]=ge:A+le:B`                         | Accepted, processed as today                                    |
| `c[FREQ]=A&c[FREQ]=M`                              | 400 (same rule, any component)                                  |
| `c[FREQ]=A,M` (single param, OR)                   | Accepted, processed as today                                    |
| `c[FREQ]=A` (single param, single value)           | Accepted, processed as today                                    |
| `c[FREQ]=A&c[CONF_STATUS]=F` (different components)| Accepted — spec explicitly allows this                          |
| No `c[...]` params at all                          | Accepted, `extractFilters` returns null as today                |

## Verification

1. `./gradlew clean build -x test` — build succeeds.
2. `./gradlew :sdmx-proxy:test` — new unit tests pass; updated test at old line 607 passes with new expectation.
3. Manual reproduction against a local proxy (BIS registry loaded):
   ```bash
   curl -i 'http://localhost:8050/api/v0/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/D.N.B.DE?c%5BTIME_PERIOD%5D=ge:2025-01-01&c%5BTIME_PERIOD%5D=le:2025-01-31&detail=full'
   ```
   Expect: HTTP 400 with body message naming `c[TIME_PERIOD]` and showing the `+`/`,` remedy.
4. Positive control — spec-compliant request returns 200 and a correctly bounded dataset:
   ```bash
   curl -i 'http://localhost:8050/api/v0/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/D.N.B.DE?c%5BTIME_PERIOD%5D=ge:2025-01-01%2Ble:2025-01-31&detail=full'
   ```
5. `./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"` — existing suite stays green (no E2E
   case currently sends duplicate `c[X]`).

## Files affected

| File                                                                                           | Type     | Change                                                  |
|------------------------------------------------------------------------------------------------|----------|---------------------------------------------------------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`     | Modified | `extractFilters` throws `FilterValidationException` on duplicate `c[X]` |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java` | Modified | Update 1 existing test + add 4 new tests                |
