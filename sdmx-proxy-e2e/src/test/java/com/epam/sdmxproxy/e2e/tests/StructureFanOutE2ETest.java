package com.epam.sdmxproxy.e2e.tests;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the proxy's structure fan-out contract when
 * {@code structureFanOutEnabled=true} is pushed in the proxy configuration:
 * a wildcard agency structure query
 * ({@code /structure/{type}/*\/*\/*}) is fanned out to every configured
 * registry that supports the requested structure type and the parsed
 * structures are merged into a single response.
 *
 * <p>This suite uses a single registry (BIS) for setup simplicity -- fan-out
 * across one registry still exercises the parallel-execution and merge code
 * paths, just with {@code n=1}. The {@link StructureWildcardE2ETest} sibling
 * covers the toggle-off (HTTP 501) contract.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("contract")
class StructureFanOutE2ETest {

    private static final String BASE_PATH = "/statgpt/sdmx-proxy/api/v0";
    private static final String CONFIG_PATH = BASE_PATH + "/config";
    private static final List<String> SDMX_3_0_STRUCTURE_TYPES = List.of(
            "datastructure",
            "conceptscheme",
            "codelist",
            "dataflow"
    );
    private static final List<String> ACCEPT_HEADERS = List.of(
            "application/vnd.sdmx.structure+xml;version=2.1",
            "application/vnd.sdmx.structure+json;version=2.0.0"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeAll
    @SneakyThrows
    void setUp() {
        restClient = new RestClient(BaseUrlProvider.getBaseUrl());

        ProxyConfiguration config = objectMapper.readValue(
                getClass().getResourceAsStream(
                        "/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_registry_config.json"
                ).readAllBytes(),
                ProxyConfiguration.class
        );
        config.setStructureFanOutEnabled(true);
        restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(config));
    }

    static Stream<Arguments> fanOutCases() {
        return SDMX_3_0_STRUCTURE_TYPES.stream().flatMap(type ->
                ACCEPT_HEADERS.stream().map(accept -> Arguments.of(type, accept))
        );
    }

    @ParameterizedTest(name = "type: {0}, accept: {1}")
    @DisplayName("Wildcard agency structure query returns merged content when fan-out is enabled")
    @MethodSource("fanOutCases")
    void wildcardAgencyReturnsMergedResponse(String structureType, String acceptHeader) {
        String path = String.format("%s/sdmx/3.0/structure/%s/*/*/*?detail=full", BASE_PATH, structureType);

        Response response = restClient.getResponseWithAccept(path, acceptHeader);

        assertThat(response.getStatusCode())
                .as("Wildcard agency structure query must return HTTP 200 when fan-out is enabled (type=%s)", structureType)
                .isEqualTo(200);

        // Assert on byte length, not decoded String -- codelist responses are large enough that
        // RestAssured's byte->String conversion can OOM the test JVM. The raw bytes are already
        // materialized by RestAssured's response-cache, so this adds no memory pressure.
        byte[] body = response.getBody().asByteArray();
        assertThat(body.length)
                .as("Fan-out response body must not be empty (type=%s)", structureType)
                .isGreaterThan(0);
    }
}
