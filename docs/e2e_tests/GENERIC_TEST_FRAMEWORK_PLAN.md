# Generic E2E Test Framework Implementation Plan

## Architecture Overview

The test framework will consist of:

1. **Base Generic Test Suite** - Abstract base class with all reusable test methods
2. **Registry-Specific Test Classes** - Extend base suite, provide config and test data
3. **Static Feature Tests** - Separate tests for fanout, bypass (not per-registry)
4. **Configuration & Data Classes** - Support classes for registry config and test data

## Implementation Structure

### 1. Core Framework Classes

#### `BaseRegistryTestSuite` (Abstract Base Class)

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/BaseRegistryTestSuite.java`

- Abstract base class containing all generic test methods
- Parameterized with `RegistryTestConfig`
- Contains tests for:
    - Structure endpoints (all structure types, detail params, references params, formats)
    - Data endpoints (key patterns, observation params, attribute/measure filtering, formats)
    - Availability endpoints (mode params, component IDs, references)
- All tests use configuration from `getRegistryConfig()` and test data from `getTestData()`
- Tests run for all supported features, expecting failures for unsupported ones

#### `RegistryTestConfig` (Configuration Class)

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/RegistryTestConfig.java`

- POJO class holding registry configuration for testing
- Fields: registry name, supported agencies, SDMX version, supported structures, endpoint configs
- Can be built from JSON config or programmatically

#### `RegistryTestData` (Test Data Class)

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/RegistryTestData.java`

- POJO class holding test data for a registry
- Fields: dataflow IDs, dataflow versions, valid keys, component IDs, etc.
- Each registry test class provides its own instance

### 2. Registry-Specific Test Classes

#### `BISRegistryTests`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/BISRegistryTests.java`

- Extends `BaseRegistryTestSuite`
- Implements `getRegistryConfig()` - returns BIS registry config
- Implements `getTestData()` - returns BIS test data (dataflow IDs, keys, etc.)
- Can override specific tests if BIS needs special handling

#### `IMFRegistryTests`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/registry/IMFRegistryTests.java`

- Extends `BaseRegistryTestSuite`
- Implements `getRegistryConfig()` - returns IMF registry config
- Implements `getTestData()` - returns IMF test data
- Tests will include codelist (IMF-specific feature)

### 3. Static Feature Tests (Not Per-Registry)

#### `FanOutTests`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/fanout/FanOutTests.java`

- Tests fanout mechanism (wildcard agency, comma-separated agencies)
- Not per-registry - tests the fanout feature itself
- Includes: `WildcardFanOutTests`, `CommaSeparatedFanOutTests`, `FanOutErrorTests`

#### `BypassTests`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/bypass/BypassTests.java`

- Tests bypass mechanism (structure, data, availability bypass)
- Not per-registry - tests the bypass feature itself
- Includes: `StructureBypassTests`, `DataBypassTests`, `AvailabilityBypassTests`

### 4. Support Utilities

#### `RegistryConfigLoader`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/support/fixtures/RegistryConfigLoader.java`

- Utility to load registry config from JSON files
- Can parse `sdmx_registries_config.json` and extract specific registry configs

#### Enhanced `TestDataProvider`

**Location**: `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/support/fixtures/TestDataProvider.java`

- Keep existing structure
- Add registry-specific data sections (BIS, IMF)
- Registry test classes can reference or extend this

## Test Method Organization

### BaseRegistryTestSuite Test Methods

#### Structure Tests

- `testStructureType()` - Parameterized by structure type (datastructure, dataflow, codelist, etc.)
- `testStructureDetail()` - Parameterized by detail value (full, allstubs, referencestubs, etc.)
- `testStructureReferences()` - Parameterized by references value (none, parents, all, etc.)
- `testStructureFormat()` - Parameterized by Accept header (XML 2.1, JSON 2.0, etc.)
- `testUnsupportedStructureType()` - Should fail for unsupported types

#### Data Tests

- `testDataKeyPatterns()` - Parameterized by key patterns (*, specific key, partial wildcard, etc.)
- `testDataObservationParams()` - Parameterized by observation params (firstN, lastN, limit, etc.)
- `testDataAttributeFiltering()` - Parameterized by attributes/measures params
- `testDataFormat()` - Parameterized by Accept header formats

#### Availability Tests

- `testAvailabilityMode()` - Parameterized by mode (exact, available)
- `testAvailabilityComponent()` - Parameterized by component ID (all, specific dimension)
- `testAvailabilityReferences()` - Parameterized by references value

## Implementation Steps

1. **Create Framework Base Classes**
    - Create `RegistryTestConfig` POJO
    - Create `RegistryTestData` POJO
    - Create abstract `BaseRegistryTestSuite` with all generic test methods
    - Create `RegistryConfigLoader` utility

2. **Implement BIS Registry Tests**
    - Create `BISRegistryTests` extending `BaseRegistryTestSuite`
    - Implement `getRegistryConfig()` with BIS config
    - Implement `getTestData()` with BIS test data
    - Verify all tests run correctly

3. **Implement IMF Registry Tests**
    - Create `IMFRegistryTests` extending `BaseRegistryTestSuite`
    - Implement `getRegistryConfig()` with IMF config
    - Implement `getTestData()` with IMF test data
    - Verify codelist tests work (IMF-specific)

4. **Implement Static Feature Tests**
    - Create `FanOutTests` (wildcard, comma-separated, error handling)
    - Create `BypassTests` (structure, data, availability bypass)
    - These are standalone, not extending base suite

5. **Refactor Existing Tests**
    - Update existing smoke tests to use new framework if applicable
    - Ensure backward compatibility

## File Structure

```
sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/
├── tests/
│   ├── framework/
│   │   ├── BaseRegistryTestSuite.java
│   │   ├── RegistryTestConfig.java
│   │   └── RegistryTestData.java
│   ├── registry/
│   │   ├── BISRegistryTests.java
│   │   └── IMFRegistryTests.java
│   ├── fanout/
│   │   ├── FanOutTests.java
│   │   ├── WildcardFanOutTests.java
│   │   ├── CommaSeparatedFanOutTests.java
│   │   └── FanOutErrorTests.java
│   ├── bypass/
│   │   ├── BypassTests.java
│   │   ├── StructureBypassTests.java
│   │   ├── DataBypassTests.java
│   │   └── AvailabilityBypassTests.java
│   ├── smoke/ (existing - keep as is or refactor)
│   └── errors/ (existing - keep as is)
└── support/
    ├── fixtures/
    │   ├── RegistryConfigLoader.java (new)
    │   └── TestDataProvider.java (enhance)
    └── ... (existing utilities)
```

## Key Design Decisions

1. **Explicit Config**: Each registry test class explicitly provides its config (not auto-discovery)
2. **All Tests Run**: Generic suite runs all tests, expecting failures for unsupported features
3. **Per-Registry Data**: Each registry test class provides its own test data
4. **Separation of Concerns**: Static feature tests (fanout, bypass) are separate from registry tests
5. **Extensibility**: Easy to add new registries by creating new test class extending base suite

## Testing Strategy

- Generic tests are parameterized and reusable
- Registry-specific config and data are provided by test classes
- Unsupported features will fail tests (expected behavior)
- Static feature tests verify fanout/bypass mechanisms work correctly
- All tests follow existing patterns (ContainerFixture, LogsGate, etc.)

## Implementation Tasks

### Task 1: Create Framework Base Classes

- Create `RegistryTestConfig` POJO
- Create `RegistryTestData` POJO
- Create abstract `BaseRegistryTestSuite` with all generic test methods
- Create `RegistryConfigLoader` utility

### Task 2: Create Config Loader

- Create `RegistryConfigLoader` utility to load registry configs from JSON files

### Task 3: Implement BIS Registry Tests

- Create `BISRegistryTests` extending `BaseRegistryTestSuite`
- Implement `getRegistryConfig()` with BIS config
- Implement `getTestData()` with BIS test data

### Task 4: Implement IMF Registry Tests

- Create `IMFRegistryTests` extending `BaseRegistryTestSuite`
- Implement `getRegistryConfig()` with IMF config
- Implement `getTestData()` with IMF test data

### Task 5: Implement Fanout Tests

- Create `FanOutTests` for wildcard, comma-separated agencies, and error handling
- These are static tests, not per-registry

### Task 6: Implement Bypass Tests

- Create `BypassTests` for structure, data, and availability bypass mechanisms
- These are static tests, not per-registry

### Task 7: Enhance Test Data Provider

- Enhance `TestDataProvider` with registry-specific data sections for BIS and IMF
