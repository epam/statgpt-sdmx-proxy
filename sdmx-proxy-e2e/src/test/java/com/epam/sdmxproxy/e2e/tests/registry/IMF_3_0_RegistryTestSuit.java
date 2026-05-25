package com.epam.sdmxproxy.e2e.tests.registry;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite;
import com.epam.sdmxproxy.e2e.tests.framework.config.RegistryTestSuitConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


public class IMF_3_0_RegistryTestSuit extends BaseRegistryTestSuite {
    private static final String DSD_WEO_PATH =
            "/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/datastructure/IMF.RES/DSD_WEO/9.0.0?references=none&detail=full";
    private static final String STRUCTURE_JSON_MEDIA_TYPE =
            "application/vnd.sdmx.structure+json;version=2.0.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @SneakyThrows
    protected ProxyConfiguration getProxyConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("imf/3_0/imf_3_0_registry_config.json").readAllBytes(), ProxyConfiguration.class);
    }

    @Override
    @SneakyThrows
    protected RegistryTestSuitConfiguration getTestConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("imf/3_0/imf_3_0_test_config.json").readAllBytes(), RegistryTestSuitConfiguration.class);
    }

    /**
     * Regression test for issue #79: the four DSD fields the proxy used to drop when converting
     * an SDMX-JSON 2.0 DSD response from IMF.
     * <ul>
     *   <li>DSD-level {@code metadata} URN (Stage 1 mapper fix)</li>
     *   <li>Dimension {@code conceptRoles} on FREQUENCY (Stage 1 mapper fix)</li>
     *   <li>Annotation {@code value} (rescued into {@code text} by ANNOTATION_VALUE_TO_TEXT fixture)</li>
     *   <li>{@code metadataAttributeUsages} on the attribute list (rescued by PRESERVE_METADATA_ATTRIBUTE_USAGES)</li>
     * </ul>
     * Driven against the live IMF SDMX Central {@code IMF.RES:DSD_WEO(9.0.0)} response.
     */
    @Test
    @DisplayName("Issue #79: DSD_WEO metadata URN, conceptRoles, annotation text, metadataAttributeUsages all preserved")
    @SneakyThrows
    void testDsdConversionFidelity_issue79() {
        Response response = restClient.getResponseWithAccept(DSD_WEO_PATH, STRUCTURE_JSON_MEDIA_TYPE);

        assertThat(response.getStatusCode())
                .as("DSD_WEO structure request must return HTTP 200")
                .isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody().asByteArray());
        JsonNode dsd = root.path("data").path("dataStructures").get(0);
        assertThat(dsd).as("dataStructures[0] must be present").isNotNull();
        assertThat(dsd.path("id").asText()).isEqualTo("DSD_WEO");
        assertThat(dsd.path("agencyID").asText()).isEqualTo("IMF.RES");

        // Issue 3: DSD `metadata` URN preserved (Stage 1 mapper fix)
        String metadataUrn = dsd.path("metadata").asText();
        assertThat(metadataUrn)
                .as("DSD `metadata` URN must point at MSD_WEO_METADATA_EXTERNAL")
                .contains("MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL");

        // Issue 4: FREQUENCY dimension conceptRoles preserved (Stage 1 mapper fix)
        JsonNode dimensions = dsd.path("dataStructureComponents").path("dimensionList").path("dimensions");
        assertThat(dimensions.isArray()).as("dimensions must be an array").isTrue();
        JsonNode frequency = null;
        for (JsonNode dim : dimensions) {
            if ("FREQUENCY".equals(dim.path("id").asText())) {
                frequency = dim;
                break;
            }
        }
        assertThat(frequency).as("FREQUENCY dimension must be present").isNotNull();
        JsonNode conceptRoles = frequency.path("conceptRoles");
        assertThat(conceptRoles.isArray() && conceptRoles.size() >= 1)
                .as("FREQUENCY conceptRoles must contain at least one entry, got: %s", conceptRoles)
                .isTrue();
        assertThat(conceptRoles.get(0).asText())
                .as("FREQUENCY conceptRoles[0] must end with SDMX_CONCEPT_ROLES(1.0).FREQ")
                .endsWith("SDMX_CONCEPT_ROLES(1.0).FREQ");

        // Issue 1: annotation `value` rescued into `text` by ANNOTATION_VALUE_TO_TEXT fixture
        JsonNode annotations = dsd.path("annotations");
        assertThat(annotations.isArray() && !annotations.isEmpty())
                .as("DSD annotations must be non-empty")
                .isTrue();
        JsonNode origin = null;
        for (JsonNode a : annotations) {
            if ("origin".equals(a.path("id").asText())) {
                origin = a;
                break;
            }
        }
        assertThat(origin).as("origin annotation must be present").isNotNull();
        assertThat(origin.path("text").asText())
                .as("origin annotation `text` must carry the original `value` (rewritten by ANNOTATION_VALUE_TO_TEXT)")
                .isNotEmpty();

        // Issue 2: metadataAttributeUsages restored by PRESERVE_METADATA_ATTRIBUTE_USAGES preserver
        JsonNode usages = dsd.path("dataStructureComponents").path("attributeList").path("metadataAttributeUsages");
        assertThat(usages.isArray() && !usages.isEmpty())
                .as("metadataAttributeUsages must be restored as a non-empty array")
                .isTrue();
    }
}
