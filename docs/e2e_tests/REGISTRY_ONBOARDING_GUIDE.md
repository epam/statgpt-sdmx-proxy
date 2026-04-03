# Registry Onboarding Guide for E2E Tests

This guide explains how to onboard a new registry into the E2E test framework. The framework uses a generic test suite
that can be reused for any registry by providing registry-specific configuration and test data.

## Overview

The E2E test framework consists of:

1. **Base Generic Test Suite** (`BaseRegistryTestSuite`) - Contains all reusable test methods
2. **Registry-Specific Test Classes** - Extend the base suite and provide registry config and test data
3. **Configuration Files** - JSON files containing registry configurations

### Test Data Structure

The framework uses **per-dataset/structure/availability configs** instead of registry-wide test data. This allows:

- **Different datasets can have different DSDs** - Each dataflow can have its own agency ID, version, and key patterns
- **Structure-specific configurations** - Each structure type (datastructure, dataflow, codelist) has its own test
  config
- **Availability-specific configurations** - Each dataset can have its own availability test configuration
- **Flexibility** - Different dataflows within the same registry can have completely different test parameters

## Prerequisites

Before onboarding a new registry, you need:

1. **Registry Configuration** - The registry must be configured in the application's `sdmx_registries_config.json`
2. **Test Data** - Valid dataflow IDs, versions, and keys that can be used for testing
3. **Registry Access** - The registry should be accessible from the test environment

## Step-by-Step Onboarding Process

### Step 1: Verify Registry Configuration

Ensure your registry is properly configured in the application's configuration file. The E2E tests read from:

```
sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/config/sdmx_registries_config.json
```

**Required Configuration Fields:**

- `name` - Registry name (e.g., "BIS", "IMF")
- `supportedAgencies` - List of agency IDs supported by this registry
- `versions` - Map of SDMX versions (e.g., "SDMX_2_1") with version-specific configs
    - `structureEndpointConfig` - Structure endpoint configuration
        - `supportedStructures` - List of supported structure types (e.g., ["datastructure", "dataflow", "codelist"])
    - `dataEndpointConfig` - Data endpoint configuration
    - `availabilityEndpointConfig` - Availability endpoint configuration

**Example Configuration:**

```json
{
  "name": "BIS",
  "description": "Bank for International Settlements",
  "supportedAgencies": ["BIS"],
  "versions": {
    "SDMX_2_1": {
      "sdmxVersion": "SDMX_2_1",
      "structureEndpointConfig": {
        "url": "https://stats.bis.org/api/v1/",
        "supportedFormats": ["XML_2_1"],
        "defaultFormat": "XML_2_1",
        "bypassEnabled": false,
        "supportedStructures": ["datastructure", "dataflow"]
      },
      "dataEndpointConfig": {
        "url": "https://stats.bis.org/api/v1/data/",
        "supportedFormats": ["XML_STRUCTURE_SPECIFIC_2_1"],
        "defaultFormat": "XML_STRUCTURE_SPECIFIC_2_1",
        "bypassEnabled": true
      },
      "availabilityEndpointConfig": {
        "url": "https://stats.bis.org/api/v1/",
        "supportedFormats": ["XML_2_1"],
        "defaultFormat": "XML_2_1",
        "bypassEnabled": true,
        "availabilityEnabled": true
      }
    }
  }
}
```

### Step 2: Discover Test Data

Before creating test classes, you need to discover valid test data from the registry:

1. **Dataflow IDs** - Query the registry to find valid dataflow IDs
    - Example: For BIS, query `https://stats.bis.org/api/v1/dataflow/BIS`
    - Note down 2-3 dataflow IDs for testing

2. **Dataflow Versions** - Usually "1.0" or "latest", verify from the registry

3. **Valid Data Keys** - Discover valid key patterns for data queries
    - Start with wildcard `*` (all data)
    - Optionally discover specific key patterns (e.g., "A.US.2023")

4. **Component IDs** - For availability tests, typically "all" or specific dimension IDs

5. **Additional Structure IDs** (if applicable):
    - Datastructure IDs (if different from dataflow)
    - Codelist IDs (if registry supports codelist)

### Step 3: Add Test Data to TestDataProvider

Add registry-specific test data constants to `TestDataProvider.java`:

```java
// Your Registry test data
public static final String YOUR_REGISTRY_DATAFLOW_1 = "YOUR_DATAFLOW_ID";
public static final String YOUR_REGISTRY_DATAFLOW_2 = "YOUR_DATAFLOW_ID_2";
public static final String YOUR_REGISTRY_DATAFLOW_VERSION = "1.0";
public static final String YOUR_REGISTRY_DATA_KEY_WILDCARD = "*";
```

**Location:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/support/fixtures/TestDataProvider.java`

### Step 4: Create Registry Test Class

Create a new test class that extends `BaseRegistryTestSuite`:

**File:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/YourRegistryTests.java`

```java
package com.epam.sdmxproxy.e2e.tests.registry;

import com.epam.sdmxproxy.e2e.support.fixtures.RegistryConfigLoader;
import com.epam.sdmxproxy.e2e.support.fixtures.TestDataProvider;
import com.epam.sdmxproxy.e2e.tests.framework.AvailabilityTestConfig;
import com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite;
import com.epam.sdmxproxy.e2e.tests.framework.DatasetTestConfig;
import com.epam.sdmxproxy.e2e.tests.framework.RegistryTestConfig;
import com.epam.sdmxproxy.e2e.tests.framework.RegistryTestData;
import com.epam.sdmxproxy.e2e.tests.framework.StructureTestConfig;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

/**
 * Your Registry tests.
 * Extends BaseRegistryTestSuite to run all generic tests with Your Registry-specific configuration and test data.
 */
@DisplayName("Your Registry Tests")
class YourRegistryTests extends BaseRegistryTestSuite {

    private static final String REGISTRY_NAME = "YOUR_REGISTRY_NAME";  // Must match config JSON
    private static final String SDMX_VERSION = "SDMX_2_1";  // Or "SDMX_3_0" if applicable

    @Override
    protected RegistryTestConfig getRegistryConfig() {
        RegistryConfigLoader loader = new RegistryConfigLoader();
        return loader.createTestConfig(REGISTRY_NAME, SDMX_VERSION);
    }

    @Override
    protected RegistryTestData getTestData() {
        return RegistryTestData.builder()
                // Dataset configs - each dataflow has its own config with agency, version, keys
                .datasetConfigs(List.of(
                        DatasetTestConfig.builder()
                                .dataflowId(TestDataProvider.YOUR_REGISTRY_DATAFLOW_1)
                                .agencyId("YOUR_AGENCY_ID")  // May differ per dataset
                                .version(TestDataProvider.YOUR_REGISTRY_DATAFLOW_VERSION)
                                .validKeys(List.of("*", "A.*.2023", "A.US.2023"))  // Dataset-specific keys
                                .validPartialKeys(List.of("A.*.2023", "*.US.*"))
                                .datastructureId("YOUR_DSD_ID")  // If different from dataflow ID
                                .datastructureVersion("1.0")  // If different from dataflow version
                                .build(),
                        DatasetTestConfig.builder()
                                .dataflowId(TestDataProvider.YOUR_REGISTRY_DATAFLOW_2)
                                .agencyId("YOUR_AGENCY_ID")
                                .version(TestDataProvider.YOUR_REGISTRY_DATAFLOW_VERSION)
                                .validKeys(List.of("*"))  // Different dataset may have different keys
                                .build()
                ))
                // Structure configs - each structure type has its own config
                .structureConfigs(List.of(
                        StructureTestConfig.builder()
                                .structureType("datastructure")
                                .agencyId("YOUR_AGENCY_ID")
                                .structureId("YOUR_DSD_ID")
                                .version("1.0")
                                .supported(true)  // Set to false if not supported
                                .build(),
                        StructureTestConfig.builder()
                                .structureType("dataflow")
                                .agencyId("YOUR_AGENCY_ID")
                                .structureId(TestDataProvider.YOUR_REGISTRY_DATAFLOW_1)
                                .version(TestDataProvider.YOUR_REGISTRY_DATAFLOW_VERSION)
                                .supported(true)
                                .build(),
                        StructureTestConfig.builder()
                                .structureType("codelist")
                                .agencyId("YOUR_AGENCY_ID")
                                .structureId("YOUR_CODELIST_ID")
                                .version("1.0")
                                .supported(true)  // Set to false if registry doesn't support codelist
                                .build(),
                        StructureTestConfig.builder()
                                .structureType("conceptscheme")
                                .agencyId("YOUR_AGENCY_ID")
                                .structureId("TEST_CONCEPTSCHEME")
                                .version("1.0")
                                .supported(false)  // Usually not supported
                                .build()
                ))
                // Availability configs - each dataset can have its own availability config
                .availabilityConfigs(List.of(
                        AvailabilityTestConfig.builder()
                                .dataflowId(TestDataProvider.YOUR_REGISTRY_DATAFLOW_1)
                                .agencyId("YOUR_AGENCY_ID")
                                .version(TestDataProvider.YOUR_REGISTRY_DATAFLOW_VERSION)
                                .validKeys(List.of("*", "A.*.2023"))  // Dataset-specific keys
                                .validComponentIds(List.of("all", "TIME_PERIOD", "MEASURE"))
                                .build(),
                        AvailabilityTestConfig.builder()
                                .dataflowId(TestDataProvider.YOUR_REGISTRY_DATAFLOW_2)
                                .agencyId("YOUR_AGENCY_ID")
                                .version(TestDataProvider.YOUR_REGISTRY_DATAFLOW_VERSION)
                                .validKeys(List.of("*"))
                                .validComponentIds(List.of("all"))
                                .build()
                ))
                .build();
    }
}
```

**Key Points:**

- `REGISTRY_NAME` must exactly match the `name` field in your JSON configuration
- `SDMX_VERSION` should match the version key in your JSON (e.g., "SDMX_2_1")
- **Dataset Configs**: Each dataflow gets its own config with agency ID, version, and valid keys
- **Structure Configs**: Each structure type (datastructure, dataflow, codelist, etc.) gets its own config
- **Availability Configs**: Each dataset can have its own availability test configuration
- Different datasets can have different agency IDs, versions, and key patterns

### Step 5: Verify Test Execution

Run the tests to verify everything works:

```bash
# Run tests for your specific registry
./gradlew :sdmx-proxy-e2e:test --tests "YourRegistryTests"

# Or run all registry tests
./gradlew :sdmx-proxy-e2e:test --tests "*RegistryTests"
```

## What Tests Are Automatically Included?

When you extend `BaseRegistryTestSuite`, your registry will automatically get:

### Structure Tests

- ✅ All supported structure types (datastructure, dataflow, codelist, etc.)
- ✅ Structure detail parameters (full, allstubs, referencestubs, etc.)
- ✅ Structure references parameters (none, parents, all, etc.)
- ✅ Structure format tests (XML 2.1, JSON 2.0)
- ✅ Unsupported structure type error tests

### Data Tests

- ✅ Data key patterns (*, specific keys, partial wildcards)
- ✅ Observation parameters (firstNObservations, lastNObservations, limit, etc.)
- ✅ Data format tests (JSON 1.0, JSON 2.0)

### Availability Tests

- ✅ Availability mode tests (exact, available)
- ✅ Availability component ID tests (all, specific dimensions)

### Error Tests

- ✅ Unsupported structure type error handling

## Example: BIS Registry

Here's a complete example of how BIS registry is configured:

**TestDataProvider.java:**

```java
// BIS test data
public static final String BIS_DATAFLOW_1 = "WS_CBS_PUB";
public static final String BIS_DATAFLOW_2 = "WS_SPP";
public static final String BIS_DATAFLOW_3 = "WS_DEBT_SEC2_PUB";
public static final String BIS_DATAFLOW_VERSION = "1.0";
public static final String BIS_DATA_KEY_WILDCARD = "*";
```

**BISRegistryTests.java:**

```java
@DisplayName("BIS Registry Tests")
class BISRegistryTests extends BaseRegistryTestSuite {

    private static final String REGISTRY_NAME = "BIS";
    private static final String SDMX_VERSION = "SDMX_2_1";

    @Override
    protected RegistryTestConfig getRegistryConfig() {
        RegistryConfigLoader loader = new RegistryConfigLoader();
        return loader.createTestConfig(REGISTRY_NAME, SDMX_VERSION);
    }

    @Override
    protected RegistryTestData getTestData() {
        return RegistryTestData.builder()
                .datasetConfigs(List.of(
                        DatasetTestConfig.builder()
                                .dataflowId(TestDataProvider.BIS_DATAFLOW_1)
                                .agencyId(TestDataProvider.BIS_AGENCY)
                                .version(TestDataProvider.BIS_DATAFLOW_VERSION)
                                .validKeys(List.of("*", "A.*.2023"))
                                .build(),
                        DatasetTestConfig.builder()
                                .dataflowId(TestDataProvider.BIS_DATAFLOW_2)
                                .agencyId(TestDataProvider.BIS_AGENCY)
                                .version(TestDataProvider.BIS_DATAFLOW_VERSION)
                                .validKeys(List.of("*"))
                                .build()
                ))
                .structureConfigs(List.of(
                        StructureTestConfig.builder()
                                .structureType("dataflow")
                                .agencyId(TestDataProvider.BIS_AGENCY)
                                .structureId(TestDataProvider.BIS_DATAFLOW_1)
                                .version(TestDataProvider.BIS_DATAFLOW_VERSION)
                                .supported(true)
                                .build()
                ))
                .availabilityConfigs(List.of(
                        AvailabilityTestConfig.builder()
                                .dataflowId(TestDataProvider.BIS_DATAFLOW_1)
                                .agencyId(TestDataProvider.BIS_AGENCY)
                                .version(TestDataProvider.BIS_DATAFLOW_VERSION)
                                .validKeys(List.of("*"))
                                .validComponentIds(List.of("all"))
                                .build()
                ))
                .build();
    }
}
```

## Example: IMF Registry (with Codelist Support)

IMF registry supports additional structure types like codelist:

**IMFRegistryTests.java:**

```java
@DisplayName("IMF Registry Tests")
class IMFRegistryTests extends BaseRegistryTestSuite {

    private static final String REGISTRY_NAME = "IMF";
    private static final String SDMX_VERSION = "SDMX_2_1";

    @Override
    protected RegistryTestConfig getRegistryConfig() {
        RegistryConfigLoader loader = new RegistryConfigLoader();
        return loader.createTestConfig(REGISTRY_NAME, SDMX_VERSION);
    }

    @Override
    protected RegistryTestData getTestData() {
        return RegistryTestData.builder()
                .datasetConfigs(List.of(
                        DatasetTestConfig.builder()
                                .dataflowId(TestDataProvider.IMF_DATAFLOW_1)
                                .agencyId(TestDataProvider.IMF_AGENCY)
                                .version(TestDataProvider.IMF_DATAFLOW_VERSION)
                                .validKeys(List.of("*", "A.*.2023"))
                                .build()
                ))
                .structureConfigs(List.of(
                        StructureTestConfig.builder()
                                .structureType("dataflow")
                                .agencyId(TestDataProvider.IMF_AGENCY)
                                .structureId(TestDataProvider.IMF_DATAFLOW_1)
                                .version(TestDataProvider.IMF_DATAFLOW_VERSION)
                                .supported(true)
                                .build(),
                        StructureTestConfig.builder()
                                .structureType("codelist")
                                .agencyId(TestDataProvider.IMF_AGENCY)
                                .structureId("CL_IMF_IFS")  // Discovered from API
                                .version("1.0")
                                .supported(true)  // IMF supports codelist
                                .build()
                ))
                .availabilityConfigs(List.of(
                        AvailabilityTestConfig.builder()
                                .dataflowId(TestDataProvider.IMF_DATAFLOW_1)
                                .agencyId(TestDataProvider.IMF_AGENCY)
                                .version(TestDataProvider.IMF_DATAFLOW_VERSION)
                                .validKeys(List.of("*"))
                                .validComponentIds(List.of("all", "TIME_PERIOD"))
                                .build()
                ))
                .build();
    }
}
```

## Minimum Required Test Data

For basic testing, you need at minimum:

1. **One dataset config** - At least one dataflow with agency ID, version, and at least one valid key
2. **One structure config** - At least one structure type (usually "dataflow") with ID and version
3. **One availability config** - At least one availability config for a dataset

**Minimal Example:**

```java
@Override
protected RegistryTestData getTestData() {
    return RegistryTestData.builder()
            // Minimum: one dataset config
            .datasetConfigs(List.of(
                    DatasetTestConfig.builder()
                            .dataflowId("YOUR_DATAFLOW_ID")
                            .agencyId("YOUR_AGENCY_ID")
                            .version("1.0")
                            .validKeys(List.of("*"))  // At minimum, wildcard
                            .build()
            ))
            // Minimum: one structure config (dataflow)
            .structureConfigs(List.of(
                    StructureTestConfig.builder()
                            .structureType("dataflow")
                            .agencyId("YOUR_AGENCY_ID")
                            .structureId("YOUR_DATAFLOW_ID")
                            .version("1.0")
                            .supported(true)
                            .build()
            ))
            // Minimum: one availability config
            .availabilityConfigs(List.of(
                    AvailabilityTestConfig.builder()
                            .dataflowId("YOUR_DATAFLOW_ID")
                            .agencyId("YOUR_AGENCY_ID")
                            .version("1.0")
                            .validKeys(List.of("*"))
                            .validComponentIds(List.of("all"))
                            .build()
            ))
            .build();
}
```

## Troubleshooting

### Issue: Registry not found

**Error:** `IllegalArgumentException: Registry not found: YOUR_REGISTRY_NAME`

**Solution:**

- Verify `REGISTRY_NAME` in your test class exactly matches the `name` field in JSON config
- Check that the JSON config file is in the correct location
- Ensure the JSON file is properly formatted

### Issue: Tests failing with 404 errors

**Error:** HTTP 404 responses

**Solution:**

- Verify dataflow IDs are correct and exist in the registry
- Check dataflow versions are valid
- Ensure registry is accessible from test environment
- Verify agency IDs match what's configured

### Issue: Unsupported structure type tests passing when they should fail

**Solution:**

- Verify `supportedStructures` in your JSON config is correct
- Check that the registry configuration is being loaded correctly

### Issue: Data endpoint tests skipped

**Solution:**

- Ensure `dataEndpointConfig` is present in your JSON configuration
- Verify the configuration is valid

## Advanced: Custom Test Overrides

If your registry needs special handling, you can override specific test methods:

```java
@DisplayName("Your Registry Tests")
class YourRegistryTests extends BaseRegistryTestSuite {
    
    // ... standard setup ...
    
    /**
     * Override if your registry has special requirements for data queries.
     */
    @Override
    @ParameterizedTest
    @MethodSource("dataKeyPatternProvider")
    @DisplayName("Test data key pattern: {0}")
    @Tag("data")
    void testDataKeyPatterns(String keyPattern) {
        // Custom implementation for your registry
        // ... your custom logic ...
    }
}
```

## Next Steps

After onboarding your registry:

1. ✅ Run all tests to verify they pass
2. ✅ Check test coverage for your registry's specific features
3. ✅ Add any registry-specific test cases if needed
4. ✅ Document any special requirements or limitations

## Summary Checklist

- [ ] Registry configured in JSON config file
- [ ] Test data discovered (dataflow IDs, versions, keys)
- [ ] Test data constants added to `TestDataProvider`
- [ ] Registry test class created extending `BaseRegistryTestSuite`
- [ ] `getRegistryConfig()` implemented
- [ ] `getTestData()` implemented with at least minimal required data
- [ ] Tests run successfully
- [ ] All generic tests pass (or expected failures documented)

## Questions?

If you encounter issues or need help:

1. Check existing examples (BIS, IMF) in the codebase
2. Review the test plan: `docs/e2e_tests/E2E_TEST_PLAN.md`
3. Review the framework design: `docs/e2e_tests/GENERIC_TEST_FRAMEWORK_PLAN.md`
