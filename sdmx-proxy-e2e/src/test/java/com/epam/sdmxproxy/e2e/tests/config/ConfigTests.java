package com.epam.sdmxproxy.e2e.tests.config;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.epam.sdmxproxy.e2e.support.fixtures.TestDataProvider.CONFIG_ENDPOINT;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_EXTRA_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Config Tests")
class ConfigTests {

    private static final String CONFIG_JSON = "sdmx_registries_config.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(BaseUrlProvider.getBaseUrl());
    }

    @Test
    @Order(100)
    @DisplayName("Config endpoint returns a parseable ProxyConfiguration with at least one registry")
    @SneakyThrows
    void defaultConfigIsPresentAndReturnedCorrectly() {

        // WHEN
        Response getConfigResponse = restClient.getResponse(CONFIG_ENDPOINT);

        // THEN
        assertThat(getConfigResponse.getStatusCode())
                .as("Config was retrieved")
                .isEqualTo(200);

        // The review-env or a long-lived bootRun may already have a config POSTed by an
        // earlier @BeforeAll, so we don't assert pristine state. We only require that
        // /config currently serves a structurally valid ProxyConfiguration.
        ProxyConfiguration config = objectMapper.readValue(
                getConfigResponse.getBody().asString(), ProxyConfiguration.class);
        assertThat(config.getConfigs())
                .as("Config response must include at least one registry configuration")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    @Order(200)
    @DisplayName("Config is being updated and returned correctly")
    @SneakyThrows
    void configEndpointWritesAndReturnsConfig() {

        //GIVEN
        String expectedConfig = new String(this.getClass().getResourceAsStream(CONFIG_JSON).readAllBytes());

        //WHEN
        Response updateConfigResponse = restClient.postResponse(
                CONFIG_ENDPOINT,
                expectedConfig
        );

        assertThat(updateConfigResponse.getStatusCode())
                .as("Config was updated")
                .isEqualTo(200);

        Response getConfigResponse = restClient.getResponse(CONFIG_ENDPOINT);

        //THEN
        assertThat(getConfigResponse.getStatusCode())
                .as("Config was retrieved")
                .isEqualTo(200);

        String actualConfig = getConfigResponse.getBody().asString();

        assertThatJson(actualConfig)
                .when(IGNORING_ARRAY_ORDER, IGNORING_EXTRA_FIELDS)
                .isEqualTo(expectedConfig);
    }
}
