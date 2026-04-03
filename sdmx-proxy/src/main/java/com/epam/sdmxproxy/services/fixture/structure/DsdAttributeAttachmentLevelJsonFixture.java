package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * JSON fixture that replaces invalid {@code attributeRelationship} values in DSD responses.
 * <p>
 * Some registries (e.g., IMF) return attributes with {@code "attributeRelationship": {"none": {}}}
 * which is not a valid SDMX value and causes parsing failures. This fixture replaces the
 * {@code sourceValue} key (e.g., "none") with a {@code fallbackValue} key (e.g., "observation").
 * <p>
 * Config parameters:
 * <ul>
 *   <li>{@code sourceValue} - the invalid key to look for (e.g., "none")</li>
 *   <li>{@code fallbackValue} - the valid key to replace it with (e.g., "observation")</li>
 * </ul>
 */
@Slf4j
@Component
public class DsdAttributeAttachmentLevelJsonFixture implements StructureFixture {

    public static final String SOURCE_VALUE = "sourceValue";
    public static final String FALLBACK_VALUE = "fallbackValue";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public StructureFixtureType getType() {
        return StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, Map<String, String> config) {
        String sourceValue = config.get(SOURCE_VALUE);
        String fallbackValue = config.get(FALLBACK_VALUE);

        if (sourceValue == null || fallbackValue == null) {
            log.warn("Missing sourceValue or fallbackValue in config, skipping");
            return input;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(input);

            fixAttributeRelationships(root, sourceValue, fallbackValue);

            return new ByteArrayInputStream(OBJECT_MAPPER.writeValueAsBytes(root));
        } catch (IOException e) {
            log.error("Failed to process JSON, returning original stream", e);
            return input;
        }
    }

    private void fixAttributeRelationships(JsonNode root, String sourceValue, String fallbackValue) {
        int count = 0;
        JsonNode dataStructures = root.path("data").path("dataStructures");
        if (dataStructures.isMissingNode() || !dataStructures.isArray()) {
            return;
        }

        for (JsonNode dsd : dataStructures) {
            JsonNode attributeList = dsd.path("dataStructureComponents").path("attributeList");
            if (attributeList.isMissingNode()) {
                continue;
            }

            count += fixAttributeRelationshipsInArray(attributeList.path("attributes"), sourceValue, fallbackValue);
            count += fixAttributeRelationshipsInArray(attributeList.path("metadataAttributeUsages"), sourceValue, fallbackValue);
        }

        if (count > 0) {
            log.debug("Replaced {} attributeRelationship '{}' -> '{}'", count, sourceValue, fallbackValue);
        }

    }

    private int fixAttributeRelationshipsInArray(JsonNode array, String sourceValue, String fallbackValue) {
        if (array.isMissingNode() || !array.isArray()) {
            return 0;
        }

        int count = 0;
        for (JsonNode item : array) {
            JsonNode attrRel = item.path("attributeRelationship");
            if (attrRel.isMissingNode() || !attrRel.isObject()) {
                continue;
            }

            if (attrRel.has(sourceValue)) {
                ObjectNode attrRelObj = (ObjectNode) attrRel;
                attrRelObj.remove(sourceValue);
                attrRelObj.putObject(fallbackValue);
                count++;
            }
        }
        return count;
    }
}
