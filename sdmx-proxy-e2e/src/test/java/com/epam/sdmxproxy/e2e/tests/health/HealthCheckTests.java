package com.epam.sdmxproxy.e2e.tests.health;

import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.logs.ContainerLogReporter;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the health/readiness endpoint.
 * Validates that the container starts correctly and the health endpoint returns expected responses.
 */
@ExtendWith({ContainerFixture.class, ContainerLogReporter.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Health Check Tests")
class HealthCheckTests {

    private static final String HEALTH_ENDPOINT = "/statgpt/sdmx-proxy/api/v0/health";
    private static final String EXPECTED_HEALTH_RESPONSE = "OK";

    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(ContainerFixture.getBaseUrl());
    }

    @Test
    @DisplayName("Health endpoint returns 200 status code")
    void healthEndpointReturns200() {
        Response response = restClient.getResponse(HEALTH_ENDPOINT);

        assertThat(response.getStatusCode())
                .as("Health endpoint should return HTTP 200")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Health endpoint returns OK in response body")
    void healthEndpointContainsOkStatus() {
        Response response = restClient.getResponse(HEALTH_ENDPOINT);

        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Health endpoint response should contain 'OK'")
                .contains(EXPECTED_HEALTH_RESPONSE);
    }

    @Test
    @DisplayName("Health endpoint response has correct content type")
    void healthEndpointHasCorrectContentType() {
        Response response = restClient.getResponse(HEALTH_ENDPOINT);

        String contentType = response.getContentType();
        assertThat(contentType)
                .as("Health endpoint should return text/plain or similar")
                .isNotNull();

        // Health endpoint returns plain text "OK"
        assertThat(contentType)
                .as("Content-Type should indicate text content")
                .contains("text");
    }

    @Test
    @DisplayName("Readiness probe works - endpoint is accessible after container startup")
    void readinessProbeWorks() {
        // This test verifies that the readiness wait strategy worked correctly
        // If we can call the health endpoint successfully, the container is ready
        Response response = restClient.getResponse(HEALTH_ENDPOINT);

        assertThat(response.getStatusCode())
                .as("Health endpoint should be accessible after container startup")
                .isEqualTo(200);

        assertThat(response.getBody().asString())
                .as("Health endpoint should return OK after container startup")
                .contains(EXPECTED_HEALTH_RESPONSE);
    }
}
