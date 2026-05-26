package com.epam.sdmxproxy.e2e.tests;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureQueryDetail;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureReferenceDetail;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.ProxyConfigPusher;
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
 * Verifies the proxy's contract that wildcard agency structure queries
 * ({@code /structure/{type}/*\/*\/*}) are rejected with HTTP 501
 * ({@code UnsupportedAgencyWildcardException}). Single suite — the contract
 * is registry-agnostic, so loading one registry config is sufficient.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("contract")
class StructureWildcardE2ETest {

    private static final String BASE_PATH = "/statgpt/sdmx-proxy/api/v0";
    private static final String CONFIG_PATH = BASE_PATH + "/config";
    private static final List<String> SDMX_3_0_STRUCTURE_TYPES = List.of(
            "datastructure",
            "conceptscheme",
            "codelist",
            "dataflow",
            "hierarchy",
            "hierarchyassociation"
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
        // Set explicitly so the suite does not silently depend on whichever default the
        // classpath sdmx_registries_config.json happens to ship (which currently is true).
        config.setStructureFanOutEnabled(false);
        ProxyConfigPusher.push(restClient, CONFIG_PATH, objectMapper.writeValueAsString(config));
    }

    static Stream<Arguments> wildcardCases() {
        return SDMX_3_0_STRUCTURE_TYPES.stream().flatMap(type ->
                ACCEPT_HEADERS.stream().flatMap(accept ->
                        Stream.of(StructureQueryDetail.values()).flatMap(detail ->
                                Stream.of(StructureReferenceDetail.values())
                                        .map(refs -> Arguments.of(type, detail, refs, accept))
                        )
                )
        );
    }

    @ParameterizedTest(name = "type: {0}, detail: {1}, references: {2}, accept: {3}")
    @DisplayName("Wildcard agency structure query must return HTTP 501")
    @MethodSource("wildcardCases")
    void wildcardAgencyReturns501(String structureType, StructureQueryDetail detail, StructureReferenceDetail references, String acceptHeader) {
        String path = String.format("%s/sdmx/3.0/structure/%s/*/*/*?references=%s&detail=%s",
                BASE_PATH, structureType, references, detail);

        Response response = restClient.getResponseWithAccept(path, acceptHeader);

        assertThat(response.getStatusCode())
                .as("Wildcard agency structure query must return HTTP 501 for type %s", structureType)
                .isEqualTo(501);
    }
}
