# E2E Test Plan for SDMX Proxy

## Overview

This document outlines the comprehensive E2E testing strategy for the SDMX Proxy application. The tests cover three main
controllers: **Structure**, **Data**, and **Availability**, along with advanced features like **Fan-Out** and **Bypass**
mechanisms.

### API Base Path

All endpoints are prefixed with: `/sdmx/proxy/api/v0`

### Configured Registries

| Registry | Agencies         | SDMX Version | Supported Structures              |
|----------|------------------|--------------|-----------------------------------|
| BIS      | `BIS`            | 2.1          | datastructure, dataflow           |
| IMF      | `IMF`, `IMF.STA` | 2.1          | datastructure, dataflow, codelist |

---

## Phase 1: Smoke Tests (Foundation)

### Goal

Verify basic connectivity and functionality for each controller with one simple test per endpoint.

### 1.1 Structure Controller - Smoke Test

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/smoke/StructureSmokeTests.java`

| Test Case          | Endpoint                                       | Expected | Validation                                       |
|--------------------|------------------------------------------------|----------|--------------------------------------------------|
| Query BIS dataflow | `GET /structure/dataflow/BIS/{dataflowId}/1.0` | HTTP 200 | Response is parseable, contains dataflow element |

**Test Data Discovery Required**: Need to find a valid BIS dataflow ID (e.g., `WS_CBS_PUB`).

### 1.2 Data Controller - Smoke Test

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/smoke/DataSmokeTests.java`

| Test Case      | Endpoint                                    | Expected | Validation                     |
|----------------|---------------------------------------------|----------|--------------------------------|
| Query BIS data | `GET /data/dataflow/BIS/{dataflowId}/1.0/*` | HTTP 200 | Response contains observations |

### 1.3 Availability Controller - Smoke Test

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/smoke/AvailabilitySmokeTests.java`

| Test Case              | Endpoint                                                | Expected | Validation                          |
|------------------------|---------------------------------------------------------|----------|-------------------------------------|
| Check BIS availability | `GET /availability/dataflow/BIS/{dataflowId}/1.0/*/all` | HTTP 200 | Response contains availability info |

---

## Phase 2: Registry Coverage Tests

### Goal

Ensure **each configured registry** works correctly for **all its supported endpoints and structure types**.

### 2.1 BIS Registry Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/BISRegistryTests.java`

**Registry Configuration**:

- Agencies: `BIS`
- SDMX Version: 2.1
- Supported Structures: `datastructure`, `dataflow`
- Data bypass: enabled
- Availability: enabled

**Test Cases**:

| # | Test Name                    | Endpoint                                                            | Description                                |
|---|------------------------------|---------------------------------------------------------------------|--------------------------------------------|
| 1 | `testBisDatastructure`       | `GET /structure/datastructure/BIS/{id}/{version}`                   | Query BIS data structure definition        |
| 2 | `testBisDataflow`            | `GET /structure/dataflow/BIS/{id}/{version}`                        | Query BIS dataflow                         |
| 3 | `testBisDataQuery`           | `GET /data/dataflow/BIS/{id}/{version}/{key}`                       | Query BIS data                             |
| 4 | `testBisDataWithFilters`     | `GET /data/dataflow/BIS/{id}/{version}/{key}?firstNObservations=10` | Query with observation limits              |
| 5 | `testBisAvailability`        | `GET /availability/dataflow/BIS/{id}/{version}/{key}/all`           | Check BIS data availability                |
| 6 | `testBisUnsupportedCodelist` | `GET /structure/codelist/BIS/{id}/{version}`                        | Should return error (not supported by BIS) |

### 2.2 IMF Registry Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/IMFRegistryTests.java`

**Registry Configuration**:

- Agencies: `IMF`, `IMF.STA`
- SDMX Version: 2.1
- Supported Structures: `datastructure`, `codelist`, `dataflow`
- Data bypass: enabled
- Availability: enabled

**Test Cases**:

| # | Test Name              | Endpoint                                                  | Description                         |
|---|------------------------|-----------------------------------------------------------|-------------------------------------|
| 1 | `testImfDatastructure` | `GET /structure/datastructure/IMF/{id}/{version}`         | Query IMF data structure definition |
| 2 | `testImfDataflow`      | `GET /structure/dataflow/IMF/{id}/{version}`              | Query IMF dataflow                  |
| 3 | `testImfCodelist`      | `GET /structure/codelist/IMF/{id}/{version}`              | Query IMF codelist (IMF-specific)   |
| 4 | `testImfSubAgency`     | `GET /structure/dataflow/IMF.STA/{id}/{version}`          | Query using sub-agency `IMF.STA`    |
| 5 | `testImfDataQuery`     | `GET /data/dataflow/IMF/{id}/{version}/{key}`             | Query IMF data                      |
| 6 | `testImfAvailability`  | `GET /availability/dataflow/IMF/{id}/{version}/{key}/all` | Check IMF data availability         |

### 2.3 Cross-Registry Validation Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/CrossRegistryValidationTests.java`

| # | Test Name                                  | Description                                                                |
|---|--------------------------------------------|----------------------------------------------------------------------------|
| 1 | `testAllRegistriesRespond`                 | Iterate over all configured registries, verify at least one endpoint works |
| 2 | `testUnknownAgencyReturnsError`            | `GET /structure/dataflow/UNKNOWN_AGENCY/...` → should return 4xx           |
| 3 | `testUnsupportedStructureTypeReturnsError` | Query structure type not supported by registry                             |

---

## Phase 3: Structure Controller Parameter Matrix

### Goal

Test all parameter combinations for structure queries.

### 3.1 Structure Types

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/structure/StructureTypeTests.java`

**Parameterized Test Matrix**:

| Structure Type  | BIS Support | IMF Support | Test Expectation     |
|-----------------|-------------|-------------|----------------------|
| `datastructure` | ✓           | ✓           | HTTP 200             |
| `dataflow`      | ✓           | ✓           | HTTP 200             |
| `codelist`      | ✗           | ✓           | BIS: error, IMF: 200 |
| `conceptscheme` | ✗           | ✗           | Both: error          |

### 3.2 Detail Parameter

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/structure/StructureDetailTests.java`

**Endpoint**: `GET /structure/{type}/{agency}/{id}/{version}?detail={value}`

| Detail Value             | Description                            | Test                              |
|--------------------------|----------------------------------------|-----------------------------------|
| `full` (default)         | Full structure with all details        | Verify complete response          |
| `allstubs`               | All artefacts as stubs                 | Verify stub format                |
| `referencestubs`         | Referenced artefacts as stubs          | Verify referenced items are stubs |
| `referencepartial`       | Referenced artefacts with partial info | Verify partial format             |
| `allcompletestubs`       | All artefacts as complete stubs        | Verify complete stub format       |
| `referencecompletestubs` | Referenced artefacts as complete stubs | Verify referenced complete stubs  |

### 3.3 References Parameter

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/structure/StructureReferencesTests.java`

**Endpoint**: `GET /structure/{type}/{agency}/{id}/{version}?references={value}`

| References Value     | Description             | Test                                  |
|----------------------|-------------------------|---------------------------------------|
| `none` (default)     | No referenced artefacts | Response contains only requested item |
| `parents`            | Direct parent artefacts | Verify parents included               |
| `parentsandsiblings` | Parents and siblings    | Verify siblings included              |
| `ancestors`          | All ancestors           | Verify full ancestry                  |
| `children`           | Direct children         | Verify children included              |
| `descendants`        | All descendants         | Verify all descendants                |
| `all`                | All references          | Verify complete reference tree        |

### 3.4 Accept Header Formats

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/structure/StructureFormatTests.java`

| Accept Header                                       | Format        | Test                 |
|-----------------------------------------------------|---------------|----------------------|
| `application/vnd.sdmx.structure+xml;version=2.1`    | SDMX-ML 2.1   | Verify XML response  |
| `application/vnd.sdmx.structure+json;version=2.0.0` | SDMX-JSON 2.0 | Verify JSON response |

---

## Phase 4: Data Controller Parameter Matrix

### Goal

Test all parameter combinations for data queries.

### 4.1 Key Patterns

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/data/DataKeyTests.java`

**Endpoint**: `GET /data/dataflow/{agency}/{id}/{version}/{key}`

| Key Pattern                           | Description                | Test                     |
|---------------------------------------|----------------------------|--------------------------|
| `*`                                   | All data (wildcard)        | Returns all observations |
| Specific key (e.g., `A.US.2023`)      | Exact dimension values     | Returns matching data    |
| Partial wildcard (e.g., `A.*.2023`)   | Some dimensions wildcarded | Returns filtered data    |
| Multiple values (e.g., `A+B.US.2023`) | OR condition               | Returns multiple series  |

### 4.2 Observation Parameters

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/data/DataObservationTests.java`

| Parameter                | Test Values              | Description                   |
|--------------------------|--------------------------|-------------------------------|
| `firstNObservations`     | 1, 10, 100               | Limit to first N observations |
| `lastNObservations`      | 1, 10, 100               | Limit to last N observations  |
| `limit`                  | 1, 100, 1000             | General result limit          |
| `dimensionAtObservation` | `TIME_PERIOD`, `MEASURE` | Observation dimension         |

### 4.3 Attribute/Measure Filtering

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/data/DataAttributeTests.java`

| Parameter         | Test Values                                             | Description              |
|-------------------|---------------------------------------------------------|--------------------------|
| `attributes`      | `dsd`, `msd`, `dataset`, `series`, `obs`, `all`, `none` | Attribute inclusion      |
| `measures`        | `all`, `none`                                           | Measure inclusion        |
| `skipEmptySeries` | `true`, `false`                                         | Skip series without data |

### 4.4 Accept Header Formats

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/data/DataFormatTests.java`

| Accept Header                                  | Format        | Test                  |
|------------------------------------------------|---------------|-----------------------|
| `application/json`                             | Generic JSON  | Verify JSON response  |
| `application/vnd.sdmx.data+json;version=1.0.0` | SDMX-JSON 1.0 | Verify format version |
| `application/vnd.sdmx.data+json;version=2.0.0` | SDMX-JSON 2.0 | Verify format version |

---

## Phase 5: Availability Controller Parameter Matrix

### Goal

Test all parameter combinations for availability queries.

### 5.1 Mode Parameter

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/availability/AvailabilityModeTests.java`

**Endpoint**: `GET /availability/dataflow/{agency}/{id}/{version}/{key}/{componentId}?mode={value}`

| Mode Value        | Description            | Test                               |
|-------------------|------------------------|------------------------------------|
| `exact` (default) | Exact key match        | Returns exact availability         |
| `available`       | Available combinations | Returns all available combinations |

### 5.2 Component ID

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/availability/AvailabilityComponentTests.java`

| Component ID          | Description      | Test                                    |
|-----------------------|------------------|-----------------------------------------|
| `all`                 | All components   | Returns all dimensions                  |
| Specific dimension ID | Single dimension | Returns specific dimension availability |

### 5.3 References Parameter

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/availability/AvailabilityReferencesTests.java`

| References Value | Description                   |
|------------------|-------------------------------|
| `none` (default) | No referenced artefacts       |
| Other values     | Similar to structure endpoint |

---

## Phase 6: Fan-Out Mechanism Tests

### Goal

Verify fan-out (parallel queries to multiple registries) works correctly.

### 6.1 Wildcard Agency Fan-Out

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/fanout/WildcardFanOutTests.java`

| # | Test Name                   | Endpoint                          | Description                                                 |
|---|-----------------------------|-----------------------------------|-------------------------------------------------------------|
| 1 | `testWildcardAgencyFanOut`  | `GET /structure/dataflow/*/*/1.0` | Query all registries with `*`                               |
| 2 | `testWildcardAggregation`   | Same as above                     | Verify response aggregates results from multiple registries |
| 3 | `testWildcardDeduplication` | Same as above                     | Verify duplicate structures are deduplicated                |

### 6.2 Comma-Separated Agencies Fan-Out

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/fanout/CommaSeparatedFanOutTests.java`

| # | Test Name                       | Endpoint                                | Description                           |
|---|---------------------------------|-----------------------------------------|---------------------------------------|
| 1 | `testCommaSeparatedAgencies`    | `GET /structure/dataflow/BIS,IMF/*/1.0` | Query specific registries             |
| 2 | `testCommaSeparatedAggregation` | Same as above                           | Verify aggregated response            |
| 3 | `testSingleRegistryNoFanOut`    | `GET /structure/dataflow/BIS/*/1.0`     | Verify no fan-out for single registry |

### 6.3 Fan-Out Error Handling

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/fanout/FanOutErrorTests.java`

| # | Test Name                 | Description                                          |
|---|---------------------------|------------------------------------------------------|
| 1 | `testInvalidAgencyInList` | Comma-separated list with invalid agency → fail fast |
| 2 | `testPartialFailure`      | One registry fails, others succeed → partial results |

---

## Phase 7: Bypass Mechanism Tests

### Goal

Verify bypass (direct passthrough without format conversion) works correctly.

### 7.1 Structure Bypass

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/bypass/StructureBypassTests.java`

| # | Test Name                            | Description                                              |
|---|--------------------------------------|----------------------------------------------------------|
| 1 | `testBypassWhenFormatMatches`        | Request format matches registry's native format → bypass |
| 2 | `testConversionWhenFormatMismatches` | Request format doesn't match → conversion occurs         |
| 3 | `testResponseFormatMatchesRequest`   | Verify response Content-Type matches Accept header       |

### 7.2 Data Bypass

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/bypass/DataBypassTests.java`

BIS has `bypassEnabled: true` for data endpoint.

| # | Test Name                         | Description                                |
|---|-----------------------------------|--------------------------------------------|
| 1 | `testBisDataBypass`               | Request BIS data in native format → bypass |
| 2 | `testBypassNoConversionArtifacts` | Verify no conversion artifacts in response |

### 7.3 Availability Bypass

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/bypass/AvailabilityBypassTests.java`

| # | Test Name                | Description                                    |
|---|--------------------------|------------------------------------------------|
| 1 | `testAvailabilityBypass` | Request availability in native format → bypass |

---

## Phase 8: Error Handling Tests

### Goal

Verify proper error responses for invalid inputs.

### 8.1 Invalid Agency Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/errors/InvalidAgencyTests.java`

| # | Test Name               | Input            | Expected  |
|---|-------------------------|------------------|-----------|
| 1 | `testNonExistentAgency` | `INVALID_AGENCY` | 4xx error |
| 2 | `testEmptyAgency`       | `` (empty)       | 4xx error |
| 3 | `testMalformedAgency`   | `123_INVALID`    | 4xx error |

### 8.2 Invalid Resource Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/errors/InvalidResourceTests.java`

| # | Test Name                      | Input                          | Expected |
|---|--------------------------------|--------------------------------|----------|
| 1 | `testNonExistentDataflow`      | Valid agency, invalid dataflow | 404      |
| 2 | `testNonExistentDatastructure` | Valid agency, invalid DSD      | 404      |
| 3 | `testInvalidStructureType`     | Invalid structure type         | 400      |

### 8.3 Invalid Context Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/errors/InvalidContextTests.java`

Data and Availability endpoints only support `context=dataflow`.

| # | Test Name                       | Input                        | Expected                          |
|---|---------------------------------|------------------------------|-----------------------------------|
| 1 | `testDatastructureContext`      | `context=datastructure`      | 400 (UnsupportedContextException) |
| 2 | `testProvisionagreementContext` | `context=provisionagreement` | 400                               |
| 3 | `testInvalidContext`            | `context=invalid`            | 400/404                           |

### 8.4 Invalid Parameter Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/errors/InvalidParameterTests.java`

| # | Test Name                    | Input                    | Expected           |
|---|------------------------------|--------------------------|--------------------|
| 1 | `testInvalidDetailValue`     | `detail=invalid`         | 400                |
| 2 | `testInvalidReferencesValue` | `references=invalid`     | 400                |
| 3 | `testInvalidAcceptHeader`    | `Accept: invalid/format` | 406 Not Acceptable |

### 8.5 Error Response Structure Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/errors/ErrorResponseStructureTests.java`

| # | Test Name                             | Description                               |
|---|---------------------------------------|-------------------------------------------|
| 1 | `testErrorResponseContainsStatusCode` | Error response includes status code       |
| 2 | `testErrorResponseContainsMessage`    | Error response includes error message     |
| 3 | `testErrorResponseFormat`             | Error response follows expected structure |

---

## Phase 9: Performance/Resilience Tests (Optional)

### Goal

Verify resilience features work under stress.

### 9.1 Timeout Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/resilience/TimeoutTests.java`

| # | Test Name                        | Description                                 |
|---|----------------------------------|---------------------------------------------|
| 1 | `testLargeDataRequestCompletes`  | Large data request completes within timeout |
| 2 | `testStreamingWorksForLargeData` | Response streaming works for large datasets |

### 9.2 Circuit Breaker Tests

**File**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/resilience/CircuitBreakerTests.java`

| # | Test Name                               | Description                              |
|---|-----------------------------------------|------------------------------------------|
| 1 | `testServiceUnavailableWhenCircuitOpen` | Verify 503 returned when circuit is open |

**Note**: May require mock registry to simulate failures.

---

## Implementation Priority

### Priority 1 (Must Have) - Critical Path

1. **Phase 1: Smoke Tests** (3 tests)
    - Validates basic functionality works
2. **Phase 2: Registry Coverage** (BIS + IMF tests)
    - Ensures all registries work correctly
3. **Phase 8: Error Handling** (invalid inputs)
    - Validates proper error responses

### Priority 2 (Should Have) - Parameter Coverage

4. **Phase 3: Structure Parameter Matrix**
    - detail, references, Accept header variations
5. **Phase 4: Data Parameter Matrix**
    - key patterns, observation limits, filtering
6. **Phase 5: Availability Parameter Matrix**
    - mode, componentId, references

### Priority 3 (Nice to Have) - Advanced Features

7. **Phase 6: Fan-Out Tests**
    - Wildcard and comma-separated agency queries
8. **Phase 7: Bypass Tests**
    - Format bypass verification
9. **Phase 9: Resilience Tests**
    - Timeout and circuit breaker behavior

---

## Test Data Discovery

### Required Test Data

Before implementing tests, discover valid resource IDs from each registry:

#### BIS Dataflows

Query: `https://stats.bis.org/api/v1/dataflow/BIS`

Example candidates:

- `WS_CBS_PUB` - Consolidated Banking Statistics
- `WS_SPP` - Statistics on Payment Systems
- `WS_DEBT_SEC2_PUB` - Debt Securities Statistics

#### IMF Dataflows

Query: `https://api.imf.org/external/sdmx/2.1/dataflow/IMF`

Example candidates:

- Research available dataflows from IMF API

### Test Data Provider Update

Update `TestDataProvider.java` with discovered real dataflow IDs:

```java
// BIS test data
public static final String BIS_DATAFLOW_1 = "WS_CBS_PUB";
public static final String BIS_DATAFLOW_2 = "WS_SPP";

// IMF test data  
public static final String IMF_DATAFLOW_1 = "..."; // Discover from API

// Valid keys for each dataflow
public static final String BIS_WS_CBS_PUB_KEY = "..."; // Discover valid key pattern
```

---

## Test Configuration

### JUnit Tags

Use tags to categorize tests for selective execution:

```java
@Tag("smoke")        // Quick smoke tests
@Tag("registry")     // Registry-specific tests
@Tag("structure")    // Structure controller tests
@Tag("data")         // Data controller tests
@Tag("availability") // Availability controller tests
@Tag("fanout")       // Fan-out mechanism tests
@Tag("bypass")       // Bypass mechanism tests
@Tag("error")        // Error handling tests
@Tag("slow")         // Long-running tests
```

### Gradle Task Configuration

```groovy
// Default: run smoke tests only
test {
    useJUnitPlatform {
        includeTags 'smoke'
    }
}

// Run all E2E tests
task allE2eTests(type: Test) {
    useJUnitPlatform()
}

// Run smoke tests only
task smokeTests(type: Test) {
    useJUnitPlatform {
        includeTags 'smoke'
    }
}

// Run registry tests
task registryTests(type: Test) {
    useJUnitPlatform {
        includeTags 'registry'
    }
}

// Run error handling tests
task errorTests(type: Test) {
    useJUnitPlatform {
        includeTags 'error'
    }
}
```

### Environment Variables

```bash
# Docker image configuration
DOCKER_IMAGE_REGISTRY=<registry-url>
DOCKER_IMAGE_TAG=<image-tag>

# Docker authentication (for private registries)
E2E_DOCKER_USER=<username>
E2E_DOCKER_PASS=<password>
```

---

## Files to Create

### Support Classes

| File                                 | Description                           |
|--------------------------------------|---------------------------------------|
| `TestDataProvider.java`              | Update with real BIS/IMF dataflow IDs |
| `StructureResponseValidator.java`    | Validate SDMX structure responses     |
| `DataResponseValidator.java`         | Validate SDMX data responses          |
| `AvailabilityResponseValidator.java` | Validate availability responses       |

### Test Classes by Priority

#### Priority 1 (Must Have)

- `StructureSmokeTests.java`
- `DataSmokeTests.java`
- `AvailabilitySmokeTests.java`
- `BISRegistryTests.java`
- `IMFRegistryTests.java`
- `CrossRegistryValidationTests.java`
- `InvalidAgencyTests.java`
- `InvalidResourceTests.java`
- `InvalidContextTests.java`

#### Priority 2 (Should Have)

- `StructureTypeTests.java`
- `StructureDetailTests.java`
- `StructureReferencesTests.java`
- `StructureFormatTests.java`
- `DataKeyTests.java`
- `DataObservationTests.java`
- `DataAttributeTests.java`
- `DataFormatTests.java`
- `AvailabilityModeTests.java`
- `AvailabilityComponentTests.java`

#### Priority 3 (Nice to Have)

- `WildcardFanOutTests.java`
- `CommaSeparatedFanOutTests.java`
- `FanOutErrorTests.java`
- `StructureBypassTests.java`
- `DataBypassTests.java`
- `AvailabilityBypassTests.java`
- `TimeoutTests.java`
- `CircuitBreakerTests.java`

---

## Test Execution Summary

| Phase                        | Tests Count (Est.) | Execution Time (Est.) |
|------------------------------|--------------------|-----------------------|
| Phase 1: Smoke               | 3                  | < 30 seconds          |
| Phase 2: Registry            | 12+                | 1-2 minutes           |
| Phase 3: Structure Params    | 20+                | 2-3 minutes           |
| Phase 4: Data Params         | 15+                | 2-3 minutes           |
| Phase 5: Availability Params | 10+                | 1-2 minutes           |
| Phase 6: Fan-Out             | 6+                 | 1 minute              |
| Phase 7: Bypass              | 6+                 | 1 minute              |
| Phase 8: Errors              | 15+                | 1-2 minutes           |
| Phase 9: Resilience          | 3+                 | 1 minute              |
| **Total**                    | **~90+ tests**     | **< 15 minutes**      |

---

## Next Steps

1. **Discover Test Data**: Query BIS and IMF APIs to find valid dataflow IDs
2. **Update TestDataProvider**: Add real test data constants
3. **Implement Phase 1**: Create smoke tests first
4. **Implement Phase 2**: Create registry coverage tests
5. **Implement Phase 8**: Create error handling tests
6. **Iterate**: Implement remaining phases based on priority

---

**Document Version**: 1.0  
**Created**: 2024  
**Author**: E2E Testing Team
