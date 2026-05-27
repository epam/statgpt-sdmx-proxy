package com.epam.sdmxproxy.e2e.tests;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.ProxyConfigPusher;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code structureFanOutEnabled=true} produces a merged response carrying
 * artefacts from every configured registry, not just the first one. Single registry is
 * already covered by {@link StructureFanOutE2ETest}; this suite pushes both BIS and IMF
 * so the merge code path is exercised with {@code n=2}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("contract")
class StructureFanOutMultiRegistryE2ETest {

    private static final String BASE_PATH = "/statgpt/sdmx-proxy/api/v0";
    private static final String CONFIG_PATH = BASE_PATH + "/config";
    private static final String JSON_2_0 = "application/vnd.sdmx.structure+json;version=2.0.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeAll
    @SneakyThrows
    void setUp() {
        restClient = new RestClient(BaseUrlProvider.getBaseUrl());

        ProxyConfiguration bis = readConfig("/com/epam/sdmxproxy/e2e/tests/registry/bis/3_0/bis_3_0_registry_config.json");
        ProxyConfiguration imf = readConfig("/com/epam/sdmxproxy/e2e/tests/registry/imf/3_0/imf_3_0_registry_config.json");

        ProxyConfiguration merged = new ProxyConfiguration();
        List<RegistryConfiguration> configs = new ArrayList<>();
        configs.addAll(bis.getConfigs());
        configs.addAll(imf.getConfigs());
        merged.setConfigs(configs);
        List<AgencyConfiguration> agencies = new ArrayList<>();
        agencies.addAll(bis.getAgencies());
        agencies.addAll(imf.getAgencies());
        merged.setAgencies(agencies);
        merged.setStructureFanOutEnabled(true);

        ProxyConfigPusher.push(restClient, CONFIG_PATH, objectMapper.writeValueAsString(merged));
    }

    @SneakyThrows
    private ProxyConfiguration readConfig(String resourcePath) {
        return objectMapper.readValue(
                getClass().getResourceAsStream(resourcePath).readAllBytes(),
                ProxyConfiguration.class
        );
    }

    @Test
    @DisplayName("Wildcard dataflow fan-out across BIS + IMF returns artefacts from both agencies")
    @SneakyThrows
    void wildcardDataflowFanOutMergesBothRegistries() {
        String path = BASE_PATH + "/sdmx/3.0/structure/dataflow/*/*/*?detail=full";

        Response response = restClient.getResponseWithAccept(path, JSON_2_0);

        assertThat(response.getStatusCode())
                .as("Fan-out across BIS + IMF must return HTTP 200")
                .isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody().asByteArray());
        JsonNode dataflows = root.path("data").path("dataflows");
        assertThat(dataflows.isArray())
                .as("Merged response must carry data.dataflows array; body root keys=%s", root.fieldNames())
                .isTrue();
        assertThat(dataflows.size())
                .as("Merged response must list at least one dataflow per participating registry")
                .isGreaterThanOrEqualTo(2);

        Set<String> agencies = new HashSet<>();
        for (JsonNode dataflow : dataflows) {
            String agency = dataflow.path("agencyID").asText(null);
            if (agency != null && !agency.isBlank()) {
                agencies.add(agency);
            }
        }
        assertThat(agencies)
                .as("Merged dataflow response must carry artefacts owned by both BIS and IMF; saw %s", agencies)
                .anyMatch(a -> a.startsWith("BIS"))
                .anyMatch(a -> a.startsWith("IMF"));
    }
}
