package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.fixture.structure.DsdAttributeAttachmentLevelJsonFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsdAttributeAttachmentLevelJsonFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DsdAttributeAttachmentLevelJsonFixture fixture = new DsdAttributeAttachmentLevelJsonFixture();

    @Test
    void shouldReturnCorrectType() {
        assertEquals(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL, fixture.getType());
    }

    @Test
    void shouldSupportJsonStructure() {
        assertTrue(fixture.supportedFormats().contains(SdmxFormat.JSON_STRUCTURE_2_0_0));
    }

    @Test
    void shouldNotSupportXmlFormats() {
        assertFalse(fixture.supportedFormats().contains(SdmxFormat.XML_STRUCTURE_2_1));
        assertFalse(fixture.supportedFormats().contains(SdmxFormat.XML_GENERIC_DATA_2_1));
        assertFalse(fixture.supportedFormats().contains(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
    }

    @Test
    @SneakyThrows
    void shouldReplaceNoneWithObservationInAttributes() {
        InputStream input = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/adapter/structure_conversion/imf/3.0/"
                        + "dsd_IMF.STA_DSD_CO2E_2.0.0_SdmxSemanticException_1207_1208.json");

        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");

        InputStream result = fixture.apply(input, config);
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributeList = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");

        // Check attributes array
        for (JsonNode attr : attributeList.path("attributes")) {
            JsonNode attrRel = attr.path("attributeRelationship");
            assertFalse(attrRel.has("none"),
                    "Attribute " + attr.path("id").asText() + " still has 'none' in attributeRelationship");
        }

        // Check metadataAttributeUsages array
        for (JsonNode usage : attributeList.path("metadataAttributeUsages")) {
            JsonNode attrRel = usage.path("attributeRelationship");
            assertFalse(attrRel.has("none"),
                    "MetadataAttributeUsage " + usage.path("metadataAttributeReference").asText()
                            + " still has 'none' in attributeRelationship");
        }
    }

    @Test
    @SneakyThrows
    void shouldReplaceNoneWithObservation_specificAttribute() {
        InputStream input = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/adapter/structure_conversion/imf/3.0/"
                        + "dsd_IMF.STA_DSD_CO2E_2.0.0_SdmxSemanticException_1207_1208.json");

        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");

        InputStream result = fixture.apply(input, config);
        JsonNode root = MAPPER.readTree(result);

        // TRANSFORMATION attribute originally had "none": {}, should now have "observation": {}
        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode transformation = null;
        for (JsonNode attr : attributes) {
            if ("TRANSFORMATION".equals(attr.path("id").asText())) {
                transformation = attr;
                break;
            }
        }

        assertFalse(transformation == null, "TRANSFORMATION attribute not found");
        JsonNode attrRel = transformation.path("attributeRelationship");
        assertFalse(attrRel.has("none"), "TRANSFORMATION still has 'none'");
        assertTrue(attrRel.has("observation"), "TRANSFORMATION should have 'observation'");
    }

    @Test
    @SneakyThrows
    void shouldPreserveExistingValidRelationships() {
        InputStream input = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/adapter/structure_conversion/imf/3.0/"
                        + "dsd_IMF.STA_DSD_CO2E_2.0.0_SdmxSemanticException_1207_1208.json");

        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");

        InputStream result = fixture.apply(input, config);
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        // SCALE should still have "dimensions" relationship (not touched by fixture)
        JsonNode scale = null;
        for (JsonNode attr : attributes) {
            if ("SCALE".equals(attr.path("id").asText())) {
                scale = attr;
                break;
            }
        }

        assertFalse(scale == null, "SCALE attribute not found");
        assertTrue(scale.path("attributeRelationship").has("dimensions"),
                "SCALE should still have 'dimensions' relationship");

        // PRECISION should still have "observation" relationship
        JsonNode precision = null;
        for (JsonNode attr : attributes) {
            if ("PRECISION".equals(attr.path("id").asText())) {
                precision = attr;
                break;
            }
        }

        assertFalse(precision == null, "PRECISION attribute not found");
        assertTrue(precision.path("attributeRelationship").has("observation"),
                "PRECISION should still have 'observation' relationship");
    }

    @Test
    @SneakyThrows
    void shouldReturnOriginalStreamWhenMissingConfig() {
        String json = "{\"data\": {}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        // Missing fallbackValue
        Map<String, String> config = Map.of("sourceValue", "none");
        InputStream result = fixture.apply(input, config);

        JsonNode root = MAPPER.readTree(result);
        assertTrue(root.has("data"));
    }

    @Test
    @SneakyThrows
    void shouldHandleJsonWithNoDataStructures() {
        String json = "{\"data\": {\"codelists\": []}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");
        InputStream result = fixture.apply(input, config);

        JsonNode root = MAPPER.readTree(result);
        assertTrue(root.path("data").has("codelists"));
    }
}
