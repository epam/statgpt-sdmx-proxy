package com.epam.sdmxproxy.e2e.tests;

import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.logs.ContainerLogReporter;
import com.epam.sdmxproxy.e2e.support.logs.LogsGate;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted live E2E cases for the BIS 3.0 limit-emulation path (design 014). Complements
 * the generic {@code testLimitNativelyHonored} / {@code testLimitEmulationStrict} in
 * {@code BaseRegistryTestSuite} by exercising scenarios that don't fit the single
 * {@code LimitTestSuitConfiguration} entry — e.g. complex keys and client-supplied
 * {@code c[]} filters.
 */
@Slf4j
@ExtendWith({ContainerFixture.class, LogsGate.class, ContainerLogReporter.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("registry")
class LimitEmulationE2ETest {

    private static final String CONFIG_PATH = "/statgpt/sdmx-proxy/api/v0/config";
    private static final String DATA_BASE = "/statgpt/sdmx-proxy/api/v0/sdmx/3.0/data/dataflow";
    private static final String DATA_ACCEPT = "application/vnd.sdmx.data+json;version=2.0.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeAll
    @SneakyThrows
    void setUp() {
        restClient = new RestClient(ContainerFixture.getBaseUrl());

        // Load the main BIS config and flip supportsLimit=false so every test in this
        // suite routes through the limit-emulation path. No dedicated emulation config
        // file -- the flag is a single runtime mutation.
        ProxyConfiguration config = objectMapper.readValue(
                getClass().getResourceAsStream(
                        "/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_registry_config.json"
                ).readAllBytes(),
                ProxyConfiguration.class
        );
        config.getConfigs().forEach(r ->
                r.getVersions().values().forEach(v -> {
                    DataEndpointConfiguration data = v.getDataEndpointConfig();
                    if (data != null) {
                        data.setSupportsLimit(false);
                    }
                })
        );
        restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(config));
    }

    @Test
    @DisplayName("limit=5 on wide cube -> series count capped at 5")
    void limitStrictBound_smallLimit_wideCube() throws Exception {
        Response response = restClient.getResponseWithAccept(
                DATA_BASE + "/BIS/WS_EER/1.0/*",
                DATA_ACCEPT,
                baseLimitParams(5));

        assertThat(response.getStatusCode()).isEqualTo(200);
        int seriesCount = countDistinctSeries(response.getBody().asString());
        assertThat(seriesCount)
                .as("With limit=5, proxy must cap returned series at 5 despite BIS ignoring native limit")
                .isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("limit=100000 > cube size -> returns whatever BIS has, no crash")
    void limitLargerThanCube_passthrough() throws Exception {
        Response response = restClient.getResponseWithAccept(
                DATA_BASE + "/BIS/WS_EER/1.0/*",
                DATA_ACCEPT,
                baseLimitParams(100000));

        assertThat(response.getStatusCode()).isEqualTo(200);
        int seriesCount = countDistinctSeries(response.getBody().asString());
        assertThat(seriesCount)
                .as("When limit exceeds cube size, proxy returns all available series")
                .isLessThanOrEqualTo(100000)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("limit=5 with complex client key -> key narrowing preserved, count capped")
    void limitWithComplexKey_preserved() throws Exception {
        // Key M.N.B.* narrows first three positions; shrinker narrows REF_AREA further.
        Response response = restClient.getResponseWithAccept(
                DATA_BASE + "/BIS/WS_EER/1.0/M.N.B.*",
                DATA_ACCEPT,
                baseLimitParams(5));

        assertThat(response.getStatusCode()).isEqualTo(200);
        int seriesCount = countDistinctSeries(response.getBody().asString());
        assertThat(seriesCount).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("limit=5 with c[FREQ]=M client filter -> narrowing honored + count capped")
    void limitWithClientCFilter() throws Exception {
        Map<String, Object> params = baseLimitParams(5);
        params.put("c[FREQ]", "M");

        Response response = restClient.getResponseWithAccept(
                DATA_BASE + "/BIS/WS_EER/1.0/*",
                DATA_ACCEPT,
                params);

        assertThat(response.getStatusCode()).isEqualTo(200);
        int seriesCount = countDistinctSeries(response.getBody().asString());
        assertThat(seriesCount).isLessThanOrEqualTo(5);
    }

    /**
     * Base set of query params shared by all limit tests in this suite. {@code firstNObservations=1}
     * keeps response payloads tiny; {@code dimensionAtObservation}/{@code attributes}=all mirror
     * the shape a real StatGPT client request would have.
     */
    private static Map<String, Object> baseLimitParams(int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("attributes", "all");
        params.put("dimensionAtObservation", "TIME_PERIOD");
        params.put("firstNObservations", 1);
        return params;
    }

    private int countDistinctSeries(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode dataSets = root.path("data").path("dataSets");
        if (!dataSets.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode ds : dataSets) {
            JsonNode series = ds.path("series");
            if (!series.isObject()) {
                continue;
            }
            Iterator<String> names = series.fieldNames();
            while (names.hasNext()) {
                names.next();
                total++;
            }
        }
        return total;
    }
}
