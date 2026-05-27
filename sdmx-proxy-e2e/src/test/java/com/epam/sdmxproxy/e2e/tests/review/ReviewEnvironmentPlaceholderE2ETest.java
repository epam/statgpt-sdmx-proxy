package com.epam.sdmxproxy.e2e.tests.review;

import com.epam.sdmxproxy.e2e.support.url.ApiKeyProvider;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review deploy smoke: DIAL Core fronts SDMX routes; {@code E2E_HOST} is the Core origin
 * (e.g. {@code https://core-statgpt-sdmx-proxy-pr-124.example.com}).
 * <p>
 * DIAL {@code Api-Key} is read from {@code E2E_PASSWORD} (same secret ai-dial-ci passes for E2E).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Review environment placeholder E2E")
@Tag("e2e")
@Tag("review")
class ReviewEnvironmentPlaceholderE2ETest {

    private static final String DATA_PATH =
            "/statgpt/sdmx-proxy/api/v0/sdmx/3.0/data/dataflow/BIS/WS_EER/1.0/*.N.B.US";

    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(BaseUrlProvider.getBaseUrl());
    }

    @Test
    @DisplayName("BIS WS_EER data query via Core returns HTTP 200")
    void bisWsEerDataQueryViaCoreReturnsOk() {
        Assumptions.assumeTrue(ApiKeyProvider.getApiKey() != null,
                "E2E_PASSWORD not set -- review-env test skipped (only runs against a DIAL-fronted deploy)");

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("c[TIME_PERIOD]", "ge:2024-05-01+le:2026-05-31");
        queryParams.put("includeHistory", "false");
        queryParams.put("limit", "1000");
        queryParams.put("attributes", "all");
        queryParams.put("dimensionAtObservation", "TIME_PERIOD");

        Response response = restClient.get(DATA_PATH)
                .header("Content-Type", "application/json")
                .queryParams(queryParams)
                .when()
                .get();

        assertThat(response.getStatusCode())
                .as("SDMX data endpoint should return HTTP 200")
                .isEqualTo(200);
    }
}
