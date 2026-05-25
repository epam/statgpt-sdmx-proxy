package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures {@code metadataAttributeUsages} arrays from raw upstream JSON and re-injects them onto
 * the matching DSD in the converted output, keyed by {@code agencyID|id|version}.
 * <p>
 * sdmx-core's bean model has no slot for {@code metadataAttributeUsages} on the DSD attribute
 * list, so the field is dropped during the parse-map-write roundtrip. This preserver is the sole
 * mechanism by which the field survives: it reads the wire bytes before parsing, and re-attaches
 * the usages onto the converted output after the writer has run.
 * <p>
 * JSON-to-JSON path only. XML output is left untouched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataAttributeUsagePreserver {

    private static final String DATA = "data";
    private static final String DATA_STRUCTURES = "dataStructures";
    private static final String COMPONENTS = "dataStructureComponents";
    private static final String ATTRIBUTE_LIST = "attributeList";
    private static final String USAGES = "metadataAttributeUsages";
    private static final String AGENCY_ID = "agencyID";
    private static final String ID = "id";
    private static final String VERSION = "version";

    private final ObjectMapper objectMapper;

    public boolean isEnabled(List<FixtureConfiguration<StructureFixtureType>> fixtures) {
        if (fixtures == null) {
            return false;
        }
        return fixtures.stream().anyMatch(f -> f.getType() == StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES);
    }

    /**
     * Captures {@code metadataAttributeUsages} arrays from each DSD in the raw JSON, keyed by
     * {@code agencyID|id|version}. Returns an empty map on parse failure (best-effort).
     */
    public Map<String, JsonNode> capture(byte[] rawJson) {
        Map<String, JsonNode> captured = new HashMap<>();
        if (rawJson == null || rawJson.length == 0) {
            return captured;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode dsds = root.path(DATA).path(DATA_STRUCTURES);
            if (!dsds.isArray()) {
                return captured;
            }
            for (JsonNode dsd : dsds) {
                String key = keyOf(dsd);
                if (key == null) {
                    continue;
                }
                JsonNode usages = dsd.path(COMPONENTS).path(ATTRIBUTE_LIST).path(USAGES);
                if (usages.isArray() && !usages.isEmpty()) {
                    captured.put(key, usages.deepCopy());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to capture metadataAttributeUsages from raw JSON; preservation skipped", e);
        }
        return captured;
    }

    /**
     * Walks the converted JSON output and re-attaches captured {@code metadataAttributeUsages}
     * arrays onto matching DSDs. Existing non-empty arrays are left intact.
     */
    public byte[] inject(byte[] convertedJson, Map<String, JsonNode> usagesByKey) {
        if (usagesByKey == null || usagesByKey.isEmpty() || convertedJson == null || convertedJson.length == 0) {
            return convertedJson;
        }
        try {
            JsonNode root = objectMapper.readTree(convertedJson);
            JsonNode dsds = root.path(DATA).path(DATA_STRUCTURES);
            if (!dsds.isArray()) {
                return convertedJson;
            }
            int injected = 0;
            for (JsonNode dsd : dsds) {
                String key = keyOf(dsd);
                if (key == null) {
                    continue;
                }
                JsonNode captured = usagesByKey.get(key);
                if (captured == null) {
                    continue;
                }
                JsonNode attributeListNode = dsd.path(COMPONENTS).path(ATTRIBUTE_LIST);
                if (!attributeListNode.isObject()) {
                    continue;
                }
                ObjectNode attributeList = (ObjectNode) attributeListNode;
                JsonNode existing = attributeList.path(USAGES);
                if (existing.isArray() && !existing.isEmpty()) {
                    continue;
                }
                attributeList.set(USAGES, (ArrayNode) captured.deepCopy());
                injected++;
            }
            if (injected > 0) {
                log.debug("Restored metadataAttributeUsages on {} DSD(s)", injected);
                return objectMapper.writeValueAsBytes(root);
            }
            return convertedJson;
        } catch (IOException e) {
            log.warn("Failed to re-inject metadataAttributeUsages into converted JSON; returning unchanged output", e);
            return convertedJson;
        }
    }

    private String keyOf(JsonNode dsd) {
        String agency = dsd.path(AGENCY_ID).asText("");
        String id = dsd.path(ID).asText("");
        String version = dsd.path(VERSION).asText("");
        if (agency.isEmpty() || id.isEmpty() || version.isEmpty()) {
            return null;
        }
        return agency + "|" + id + "|" + version;
    }
}
