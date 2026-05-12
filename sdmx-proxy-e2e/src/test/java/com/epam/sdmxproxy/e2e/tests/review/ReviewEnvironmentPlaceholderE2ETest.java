package com.epam.sdmxproxy.e2e.tests.review;

import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the review deploy pipeline. The review workflow currently supplies
 * the ai-dial-chat base URL via {@code E2E_HOST}, not the sdmx-proxy service URL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Review environment placeholder E2E")
@Tag("e2e")
@Tag("review")
class ReviewEnvironmentPlaceholderE2ETest {

    private static final String HEALTH_ENDPOINT = "/api/health";
    private static final String EXPECTED_HEALTH_RESPONSE = "Healthy";

    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(BaseUrlProvider.getBaseUrl());
    }

    @Test
    @DisplayName("Review chat health endpoint is reachable")
    void reviewChatHealthEndpointIsReachable() {
        Response response = restClient.getResponse(HEALTH_ENDPOINT);

        assertThat(response.getStatusCode())
                .as("Chat health endpoint should return HTTP 200")
                .isEqualTo(200);

        assertThat(response.getBody().asString())
                .as("Chat health endpoint should return Healthy")
                .contains(EXPECTED_HEALTH_RESPONSE);
    }
}
