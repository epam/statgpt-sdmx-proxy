package com.epam.sdmxproxy.e2e.support.fixtures;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

/**
 * Provides test data for parameterized tests.
 * Supplies agency IDs, resource IDs, versions, keys for testing.
 */
public class TestDataProvider {
    //ENDPOINTS
    public static final String CONFIG_ENDPOINT = "/statgpt/sdmx-proxy-config-server/api/v0/config";

    // Common test agency IDs
    public static final String TEST_AGENCY_1 = "TEST_AGENCY";
    public static final String TEST_AGENCY_2 = "TEST_AGENCY_2";
    public static final String ECB_AGENCY = "ECB";
    public static final String IMF_AGENCY = "IMF";
    public static final String IMF_SUB_AGENCY = "IMF.STA";
    public static final String BIS_AGENCY = "BIS";
    public static final String OECD_AGENCY = "OECD";

    // Common test resource IDs
    public static final String TEST_RESOURCE_1 = "TEST_RESOURCE";
    public static final String TEST_RESOURCE_2 = "TEST_RESOURCE_2";
    public static final String TEST_DATASET = "TEST_DATASET";
    public static final String TEST_DATAFLOW = "TEST_DATAFLOW";

    // BIS test data (from E2E_TEST_PLAN.md)
    public static final String BIS_DATAFLOW_1 = "WS_CBS_PUB";  // Consolidated Banking Statistics
    public static final String BIS_DATAFLOW_2 = "WS_SPP";       // Statistics on Payment Systems
    public static final String BIS_DATAFLOW_3 = "WS_DEBT_SEC2_PUB";  // Debt Securities Statistics
    public static final String BIS_DATAFLOW_VERSION = "1.0";
    // BIS data keys - using wildcard for now, will be discovered from API
    public static final String BIS_DATA_KEY_WILDCARD = "*";

    // IMF test data (to be discovered from API)
    public static final String IMF_DATAFLOW_1 = "IFS";  // International Financial Statistics (common IMF dataflow)
    public static final String IMF_DATAFLOW_VERSION = "1.0";
    // IMF data keys - using wildcard for now
    public static final String IMF_DATA_KEY_WILDCARD = "*";

    // Common test versions
    public static final String VERSION_1_0 = "1.0";
    public static final String VERSION_2_0 = "2.0";
    public static final String VERSION_LATEST = "latest";
    public static final String VERSION_ALL = "all";

    // Common test keys
    public static final String TEST_KEY_1 = "A.B.C";
    public static final String TEST_KEY_2 = "X.Y.Z";
    public static final String TEST_KEY_WILDCARD = "*.*.*";

    // Common test component IDs
    public static final String TEST_COMPONENT_1 = "OBS_VALUE";
    public static final String TEST_COMPONENT_2 = "TIME_PERIOD";

    // SDMX versions
    public static final String SDMX_V2_1 = "2.1";
    public static final String SDMX_V3_0 = "3.0";

    // Accept headers for SDMX endpoints
    // Structure endpoints
    public static final String ACCEPT_STRUCTURE_XML_2_1 = "application/vnd.sdmx.structure+xml;version=2.1";
    public static final String ACCEPT_STRUCTURE_JSON_2_0 = "application/vnd.sdmx.structure+json;version=2.0.0";

    // Data endpoints
    public static final String ACCEPT_DATA_JSON_GENERIC = "application/json";
    public static final String ACCEPT_DATA_JSON_1_0 = "application/vnd.sdmx.data+json;version=1.0.0";
    public static final String ACCEPT_DATA_JSON_2_0 = "application/vnd.sdmx.data+json;version=2.0.0";

    // Availability endpoints (use same as data)
    public static final String ACCEPT_AVAILABILITY_JSON_2_0 = "application/vnd.sdmx.data+json;version=2.0.0";

    /**
     * Provides arguments for testing different agency IDs.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> agencyIds() {
        return Stream.of(
                Arguments.of(TEST_AGENCY_1),
                Arguments.of(ECB_AGENCY),
                Arguments.of(IMF_AGENCY),
                Arguments.of(OECD_AGENCY)
        );
    }

    /**
     * Provides arguments for testing different resource IDs.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> resourceIds() {
        return Stream.of(
                Arguments.of(TEST_RESOURCE_1),
                Arguments.of(TEST_DATASET),
                Arguments.of(TEST_DATAFLOW)
        );
    }

    /**
     * Provides arguments for testing different versions.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of(VERSION_1_0),
                Arguments.of(VERSION_2_0),
                Arguments.of(VERSION_LATEST),
                Arguments.of(VERSION_ALL)
        );
    }

    /**
     * Provides arguments for testing different keys.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> keys() {
        return Stream.of(
                Arguments.of(TEST_KEY_1),
                Arguments.of(TEST_KEY_2),
                Arguments.of(TEST_KEY_WILDCARD)
        );
    }

    /**
     * Provides arguments for testing structure types.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> structureTypes() {
        return Stream.of(
                Arguments.of("datastructure"),
                Arguments.of("dataflow"),
                Arguments.of("codelist"),
                Arguments.of("conceptscheme")
        );
    }

    /**
     * Provides arguments for testing data contexts.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> dataContexts() {
        return Stream.of(
                Arguments.of("dataflow"),
                Arguments.of("datastructure")
        );
    }

    /**
     * Provides arguments for testing SDMX versions.
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> sdmxVersions() {
        return Stream.of(
                Arguments.of(SDMX_V2_1),
                Arguments.of(SDMX_V3_0)
        );
    }

    /**
     * Provides arguments for testing invalid agency IDs (should return 400/404).
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> invalidAgencyIds() {
        return Stream.of(
                Arguments.of("INVALID_AGENCY"),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("123_INVALID")
        );
    }

    /**
     * Provides arguments for testing invalid resource IDs (should return 400/404).
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> invalidResourceIds() {
        return Stream.of(
                Arguments.of("INVALID_RESOURCE"),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("123_INVALID")
        );
    }

    /**
     * Provides arguments for testing invalid versions (should return 400/404).
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> invalidVersions() {
        return Stream.of(
                Arguments.of("INVALID_VERSION"),
                Arguments.of("999.999"),
                Arguments.of("")
        );
    }

    /**
     * Provides combined arguments for structure endpoint testing.
     * Format: (structureType, agencyId, resourceId, version)
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> structureEndpointParams() {
        return Stream.of(
                Arguments.of("dataflow", TEST_AGENCY_1, TEST_DATAFLOW, VERSION_1_0),
                Arguments.of("datastructure", TEST_AGENCY_1, TEST_RESOURCE_1, VERSION_1_0),
                Arguments.of("codelist", TEST_AGENCY_1, TEST_RESOURCE_1, VERSION_1_0)
        );
    }

    /**
     * Provides combined arguments for data endpoint testing.
     * Format: (context, agencyId, resourceId, version, key)
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> dataEndpointParams() {
        return Stream.of(
                Arguments.of("dataflow", TEST_AGENCY_1, TEST_DATAFLOW, VERSION_1_0, TEST_KEY_1),
                Arguments.of("dataflow", TEST_AGENCY_1, TEST_DATAFLOW, VERSION_1_0, TEST_KEY_WILDCARD)
        );
    }

    /**
     * Provides combined arguments for availability endpoint testing.
     * Format: (context, agencyId, resourceId, version, key, componentId)
     *
     * @return Stream of Arguments for parameterized tests
     */
    public static Stream<Arguments> availabilityEndpointParams() {
        return Stream.of(
                Arguments.of("dataflow", TEST_AGENCY_1, TEST_DATAFLOW, VERSION_1_0, TEST_KEY_1, TEST_COMPONENT_1),
                Arguments.of("dataflow", TEST_AGENCY_1, TEST_DATAFLOW, VERSION_1_0, TEST_KEY_1, TEST_COMPONENT_2)
        );
    }
}
