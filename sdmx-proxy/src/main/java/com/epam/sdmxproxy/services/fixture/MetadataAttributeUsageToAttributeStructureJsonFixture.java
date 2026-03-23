package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.FixtureType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON fixture that converts {@code metadataAttributeUsages} in a DSD's attribute list
 * into regular {@code attributes} by resolving missing details from the referenced
 * {@code MetadataStructure}.
 * <p>
 * SDMX Core does not support {@code metadataAttributeUsages} in DSD, but some registries
 * (e.g., IMF) include them. This fixture bridges the gap by:
 * <ol>
 *   <li>Parsing the {@code metadata} URN on each DataStructure to locate the referenced MSD</li>
 *   <li>Looking up the MSD in {@code data.metadataStructures[]}</li>
 *   <li>For each usage, building a proper attribute from the usage and the MSD's metadata attribute</li>
 *   <li>Appending the new attributes to {@code attributeList.attributes[]}</li>
 *   <li>Removing the {@code metadataAttributeUsages} array</li>
 * </ol>
 * If the referenced MetadataStructure is not present in the JSON, the DSD is skipped.
 * <p>
 * No configuration parameters are required.
 */
@Slf4j
@Component
public class MetadataAttributeUsageToAttributeStructureJsonFixture implements Fixture {

    /**
     * Extracts agencyID (group 1) and artefact id (group 2) from an SDMX URN.
     * Example: {@code urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0+.0)}
     * yields {@code IMF.RES} and {@code MSD_WEO_METADATA_EXTERNAL}.
     */
    static final Pattern URN_AGENCY_ID_PATTERN = Pattern.compile("=([^:]+):([^(]+)");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public FixtureType getType() {
        return FixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, Map<String, String> config) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(input);

            Map<String, Map<String, JsonNode>> msdIndex = buildMetadataStructureIndex(root);
            convertUsages(root, msdIndex);

            return new ByteArrayInputStream(OBJECT_MAPPER.writeValueAsBytes(root));
        } catch (IOException e) {
            log.error("Failed to process JSON for metadata attribute usage conversion, returning original stream", e);
            return input;
        }
    }

    /**
     * Builds an index of metadata structures: {@code "agencyID:id"} -> (attribute id -> metadataAttribute node).
     */
    private Map<String, Map<String, JsonNode>> buildMetadataStructureIndex(JsonNode root) {
        Map<String, Map<String, JsonNode>> index = new HashMap<>();

        JsonNode metadataStructures = root.path("data").path("metadataStructures");
        if (metadataStructures.isMissingNode() || !metadataStructures.isArray()) {
            return index;
        }

        for (JsonNode msd : metadataStructures) {
            String agencyID = msd.path("agencyID").asText("");
            String id = msd.path("id").asText("");
            if (agencyID.isEmpty() || id.isEmpty()) {
                continue;
            }

            String key = agencyID + ":" + id;
            Map<String, JsonNode> attrMap = new HashMap<>();

            JsonNode metadataAttributes = msd
                    .path("metadataStructureComponents")
                    .path("metadataAttributeList")
                    .path("metadataAttributes");

            if (metadataAttributes.isArray()) {
                for (JsonNode ma : metadataAttributes) {
                    String maId = ma.path("id").asText("");
                    if (!maId.isEmpty()) {
                        attrMap.put(maId, ma);
                    }
                }
            }

            index.put(key, attrMap);
        }

        return index;
    }

    private void convertUsages(JsonNode root, Map<String, Map<String, JsonNode>> msdIndex) {
        JsonNode dataStructures = root.path("data").path("dataStructures");
        if (dataStructures.isMissingNode() || !dataStructures.isArray()) {
            return;
        }

        int totalConverted = 0;

        for (JsonNode dsd : dataStructures) {
            String metadataUrn = dsd.path("metadata").asText("");
            if (metadataUrn.isEmpty()) {
                continue;
            }

            Map<String, JsonNode> metadataAttributes = resolveMsd(metadataUrn, msdIndex);
            if (metadataAttributes == null) {
                log.debug("MetadataStructure not found in JSON for URN {}, skipping DSD", metadataUrn);
                continue;
            }

            JsonNode attributeList = dsd.path("dataStructureComponents").path("attributeList");
            if (attributeList.isMissingNode() || !attributeList.isObject()) {
                continue;
            }

            JsonNode usages = attributeList.path("metadataAttributeUsages");
            if (usages.isMissingNode() || !usages.isArray()) {
                continue;
            }

            ArrayNode attributes = ensureAttributesArray((ObjectNode) attributeList);

            for (JsonNode usage : usages) {
                String ref = usage.path("metadataAttributeReference").asText("");
                if (ref.isEmpty()) {
                    continue;
                }

                JsonNode metadataAttr = metadataAttributes.get(ref);
                if (metadataAttr == null) {
                    log.warn("Metadata attribute '{}' not found in MSD, skipping", ref);
                    continue;
                }

                ObjectNode attribute = buildAttribute(usage, metadataAttr, ref);
                attributes.add(attribute);
                totalConverted++;
            }

            ((ObjectNode) attributeList).remove("metadataAttributeUsages");
        }

        if (totalConverted > 0) {
            log.debug("Converted {} metadataAttributeUsage(s) to attribute(s)", totalConverted);
        }
    }

    private Map<String, JsonNode> resolveMsd(String urn, Map<String, Map<String, JsonNode>> msdIndex) {
        Matcher matcher = URN_AGENCY_ID_PATTERN.matcher(urn);
        if (!matcher.find()) {
            return null;
        }
        String key = matcher.group(1) + ":" + matcher.group(2);
        return msdIndex.get(key);
    }

    private ArrayNode ensureAttributesArray(ObjectNode attributeList) {
        JsonNode existing = attributeList.path("attributes");
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        return attributeList.putArray("attributes");
    }

    private ObjectNode buildAttribute(JsonNode usage, JsonNode metadataAttr, String id) {
        ObjectNode attr = OBJECT_MAPPER.createObjectNode();

        attr.set("annotations", usage.path("annotations").deepCopy());
        attr.put("id", id);
        attr.put("conceptIdentity", metadataAttr.path("conceptIdentity").asText(""));

        int minOccurs = metadataAttr.path("minOccurs").asInt(0);
        attr.put("isMandatory", minOccurs > 0);

        attr.putArray("conceptRoles");
        attr.set("attributeRelationship", usage.path("attributeRelationship").deepCopy());

        return attr;
    }
}
