package com.epam.sdmxproxy.e2e.tests.config;

import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.logs.ContainerLogReporter;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.epam.sdmxproxy.e2e.support.fixtures.TestDataProvider.CONFIG_ENDPOINT;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_EXTRA_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith({ContainerFixture.class, ContainerLogReporter.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Config Tests")
class ConfigTests {

    private static final String CONFIG_JSON = "sdmx_registries_config.json";

    private RestClient restClient;

    @BeforeAll
    void setUp() {
        this.restClient = new RestClient(ContainerFixture.getBaseUrl());
    }

    @Test
    @Order(100)
    @DisplayName("Default config is returned correctly")
    @SneakyThrows
    void defaultConfigIsPresentAndReturnedCorrectly() {

        //WHEN
        Response getConfigResponse = restClient.getResponse(CONFIG_ENDPOINT);

        //THEN
        assertThat(getConfigResponse.getStatusCode())
                .as("Config was retrieved")
                .isEqualTo(200);
        assertFalse(getConfigResponse.getBody().asString().contains("TEST_CONFIG"));
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
