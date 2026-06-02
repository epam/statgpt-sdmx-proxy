package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.jsdmx.infomodel.sdmx30.SdmxUrn;
import com.epam.jsdmx.infomodel.sdmx30.UrnComponents;
import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.services.adapter.GenericRegistryAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Folds a DSD's SDMX 3.0 {@code metadataAttributeUsages} into regular {@code attributes} for XML 2.1
 * output (design 032).
 * <p>
 * SDMX-ML 2.1 has no {@code MetadataAttributeUsage} element, so sdmx-core drops the field on a
 * JSON 2.0 -> XML 2.1 conversion. IMF's own 2.1 endpoint instead folds each usage into the DSD
 * {@code AttributeList} as a regular {@code DataAttribute}; this service reproduces that
 * representation. For each DSD it resolves the referenced MSD (already present in the response, or
 * side-fetched on demand), turns every usage into an attribute carrying the MSD attribute's
 * {@code conceptIdentity}/{@code minOccurs} and the usage's {@code annotations}/{@code attributeRelationship},
 * then drops both {@code metadataAttributeUsages} and any {@code metadataStructures} so the spliced
 * MSD never reaches the converter.
 * <p>
 * Invoked directly by {@code AdapterRouterImpl} only on the XML 2.1 structure path -- never for JSON
 * output, where folding would mimic the non-standard SDMX-PLUS behaviour removed in design 027.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataAttributeUsageFolder {

    private static final String DATA = "data";
    private static final String DATA_STRUCTURES = "dataStructures";
    private static final String METADATA_STRUCTURES = "metadataStructures";
    private static final String METADATA = "metadata";
    private static final String COMPONENTS = "dataStructureComponents";
    private static final String ATTRIBUTE_LIST = "attributeList";
    private static final String ATTRIBUTES = "attributes";
    private static final String USAGES = "metadataAttributeUsages";

    private final GenericRegistryAdapter genericRegistryAdapter;
    private final ObjectMapper objectMapper;

    /**
     * Returns {@code rawJson} with every DSD's {@code metadataAttributeUsages} folded into
     * {@code attributes}. Best-effort: on any parse failure or when no MSD can be resolved the bytes
     * are returned unchanged (the usages are then simply dropped by the downstream converter, as
     * before this feature).
     */
    public byte[] foldForXml21(byte[] rawJson, TranslatedStructureQuery dsdQuery) {
        if (rawJson == null || rawJson.length == 0) {
            return rawJson;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode dataNode = root.path(DATA);
            JsonNode dsds = dataNode.path(DATA_STRUCTURES);
            if (!dataNode.isObject() || !dsds.isArray()) {
                return rawJson;
            }
            ObjectNode data = (ObjectNode) dataNode;

            Map<String, Map<String, JsonNode>> msdIndex = new HashMap<>();
            indexMetadataStructures(data.path(METADATA_STRUCTURES), msdIndex);

            int converted = 0;
            for (JsonNode dsd : dsds) {
                converted += foldDsd(dsd, msdIndex, dsdQuery);
            }
            data.remove(METADATA_STRUCTURES);

            if (converted == 0) {
                return rawJson;
            }
            log.debug("Folded {} metadataAttributeUsage(s) into attributes for XML 2.1 output", converted);
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            log.warn("Failed to fold metadataAttributeUsages into attributes; usages will be dropped on conversion", e);
            return rawJson;
        }
    }

    private int foldDsd(JsonNode dsd, Map<String, Map<String, JsonNode>> msdIndex, TranslatedStructureQuery dsdQuery) {
        String metadataUrn = dsd.path(METADATA).asText("");
        JsonNode attributeListNode = dsd.path(COMPONENTS).path(ATTRIBUTE_LIST);
        JsonNode usages = attributeListNode.path(USAGES);
        if (metadataUrn.isEmpty() || !attributeListNode.isObject() || !usages.isArray() || usages.isEmpty()) {
            return 0;
        }

        Map<String, JsonNode> metadataAttributes = resolveMsd(metadataUrn, msdIndex, dsdQuery);
        if (metadataAttributes == null) {
            log.debug("MSD not resolvable for URN {}, leaving metadataAttributeUsages unfolded", metadataUrn);
            return 0;
        }

        ObjectNode attributeList = (ObjectNode) attributeListNode;
        ArrayNode attributes = ensureAttributesArray(attributeList);
        int converted = 0;
        for (JsonNode usage : usages) {
            String ref = usage.path("metadataAttributeReference").asText("");
            JsonNode metadataAttr = ref.isEmpty() ? null : metadataAttributes.get(ref);
            if (metadataAttr == null) {
                log.warn("Metadata attribute '{}' not found in MSD, skipping", ref);
                continue;
            }
            attributes.add(buildAttribute(usage, metadataAttr, ref));
            converted++;
        }
        attributeList.remove(USAGES);
        return converted;
    }

    /**
     * Resolves the MSD referenced by {@code metadataUrn} to a map of {@code attributeId -> metadataAttribute}.
     * Looks in the already-built index first; on a miss, side-fetches the MSD and indexes it. Returns
     * {@code null} when the MSD cannot be resolved.
     */
    private Map<String, JsonNode> resolveMsd(String metadataUrn, Map<String, Map<String, JsonNode>> msdIndex, TranslatedStructureQuery dsdQuery) {
        if (!SdmxUrn.isUrn(metadataUrn)) {
            return null;
        }
        UrnComponents urn = SdmxUrn.getUrnComponents(metadataUrn);
        String key = urn.getAgency() + ":" + urn.getId();
        Map<String, JsonNode> indexed = msdIndex.get(key);
        if (indexed != null) {
            return indexed;
        }
        JsonNode msd = fetchMsd(urn, dsdQuery);
        if (msd == null) {
            return null;
        }
        indexMetadataStructure(msd, msdIndex);
        return msdIndex.get(key);
    }

    /**
     * Internal raw-JSON side-fetch of the MSD. Reuses the DSD query's resolved registry/version config
     * (so it bypasses client-facing structure-type validation) and its registry return format (JSON 2.0,
     * the only format this fold runs for). Returns the first {@code data.metadataStructures[]} node, or
     * {@code null} on any failure.
     */
    private JsonNode fetchMsd(UrnComponents urn, TranslatedStructureQuery dsdQuery) {
        TranslatedStructureQuery msdQuery = TranslatedStructureQuery.builder()
                .registryConfiguration(dsdQuery.getRegistryConfiguration())
                .versionConfiguration(dsdQuery.getVersionConfiguration())
                .structure(new Structure("metadatastructure", urn.getAgency(), urn.getId(), urn.getVersion()))
                .references("none")
                .detail("full")
                .contentType(dsdQuery.getContentType())
                .registryReturnFormat(dsdQuery.getRegistryReturnFormat())
                .build();
        try (InputStream in = genericRegistryAdapter.getStructures(msdQuery)) {
            if (in == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(in.readAllBytes());
            JsonNode arr = root.path(DATA).path(METADATA_STRUCTURES);
            return (arr.isArray() && !arr.isEmpty()) ? arr.get(0) : null;
        } catch (Exception e) {
            log.warn("MSD side-fetch failed for {}:{}: {}", urn.getAgency(), urn.getId(), e.getMessage());
            return null;
        }
    }

    private void indexMetadataStructures(JsonNode metadataStructures, Map<String, Map<String, JsonNode>> index) {
        if (metadataStructures.isArray()) {
            for (JsonNode msd : metadataStructures) {
                indexMetadataStructure(msd, index);
            }
        }
    }

    private void indexMetadataStructure(JsonNode msd, Map<String, Map<String, JsonNode>> index) {
        String agency = msd.path("agencyID").asText("");
        String id = msd.path("id").asText("");
        if (agency.isEmpty() || id.isEmpty()) {
            return;
        }
        Map<String, JsonNode> attrMap = new HashMap<>();
        JsonNode metadataAttributes = msd.path("metadataStructureComponents").path("metadataAttributeList").path("metadataAttributes");
        if (metadataAttributes.isArray()) {
            for (JsonNode ma : metadataAttributes) {
                String maId = ma.path("id").asText("");
                if (!maId.isEmpty()) {
                    attrMap.put(maId, ma);
                }
            }
        }
        index.put(agency + ":" + id, attrMap);
    }

    private ArrayNode ensureAttributesArray(ObjectNode attributeList) {
        JsonNode existing = attributeList.path(ATTRIBUTES);
        return existing.isArray() ? (ArrayNode) existing : attributeList.putArray(ATTRIBUTES);
    }

    private ObjectNode buildAttribute(JsonNode usage, JsonNode metadataAttr, String id) {
        ObjectNode attr = objectMapper.createObjectNode();
        attr.set("annotations", usage.path("annotations").deepCopy());
        attr.put("id", id);
        attr.put("conceptIdentity", metadataAttr.path("conceptIdentity").asText(""));
        attr.put("isMandatory", metadataAttr.path("minOccurs").asInt(0) > 0);
        attr.putArray("conceptRoles");
        attr.set("attributeRelationship", usage.path("attributeRelationship").deepCopy());
        return attr;
    }
}
