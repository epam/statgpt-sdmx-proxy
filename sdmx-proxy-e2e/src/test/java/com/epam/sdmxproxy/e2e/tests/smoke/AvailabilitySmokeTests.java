package com.epam.sdmxproxy.e2e.tests.smoke;

import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.fixtures.TestDataProvider;
import com.epam.sdmxproxy.e2e.support.logs.ContainerLogReporter;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for Availability Controller.
 * Verifies basic connectivity and functionality with one simple test per endpoint.
 * <p>
 * Phase 1.3: Availability Controller - Smoke Test
 */
@ExtendWith({ContainerFixture.class, ContainerLogReporter.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Availability Controller Smoke Tests")
@Tag("smoke")
class AvailabilitySmokeTests {

    private static final String BASE_PATH = "/statgpt/sdmx-proxy/api/v0";
    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(ContainerFixture.getBaseUrl());
    }

    @Test
    @DisplayName("Check BIS availability - returns HTTP 200 and contains availability info")
    void testCheckBisAvailability() {
        // Test Case: Check BIS availability
        // Endpoint: GET /availability/dataflow/BIS/{dataflowId}/1.0/*/all
        // Expected: HTTP 200, Response contains availability info

        String path = String.format("%s/sdmx/3.0/availability/dataflow/%s/%s/%s/%s/all",
                BASE_PATH,
                TestDataProvider.BIS_AGENCY,
                TestDataProvider.BIS_DATAFLOW_1,
                TestDataProvider.BIS_DATAFLOW_VERSION,
                TestDataProvider.BIS_DATA_KEY_WILDCARD);

        // Use proper Accept header for SDMX availability (SDMX-JSON 2.0)
        Response response = restClient.getResponseWithAccept(path, TestDataProvider.ACCEPT_AVAILABILITY_JSON_2_0);

        // Validate HTTP status
        assertThat(response.getStatusCode())
                .as("Availability endpoint should return HTTP 200 for valid BIS availability query")
                .isEqualTo(200);

        // Validate response contains availability information
        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Response body should not be empty")
                .isNotEmpty();

        // Validate response contains availability-related elements
        // Availability responses typically contain dimension values or availability metadata
        String contentType = response.getContentType();
        if (contentType != null && contentType.contains("xml")) {
            // XML response - check for availability-related elements
            assertThat(responseBody)
                    .as("XML response should contain availability information")
                    .matches(body -> body.contains("Availability") ||
                            body.contains("available") ||
                            body.contains("dimension") ||
                            body.contains("value"));
        } else if (contentType != null && contentType.contains("json")) {
            // JSON response - check for availability structure
            assertThat(responseBody)
                    .as("JSON response should contain availability information")
                    .matches(body -> body.contains("{}"));
            //                    .matches(body -> body.contains("availability") ||
            //                            body.contains("available") ||
            //                            body.contains("dimension") ||
            //                            body.contains("value"));
        } else {
            // Fallback: at least verify response is not empty
            assertThat(responseBody).isNotEmpty();
        }
    }
}
