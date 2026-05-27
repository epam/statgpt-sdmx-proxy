package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the upstream SDMX-JSON 2.0 data response's attribute-related
 * sections and re-injects them onto the converted output. Specifically:
 *
 * <ul>
 *   <li><b>Structure side</b>:
 *       {@code data.structures[*].attributes.{dataSet,dimensionGroup,series,observation}}
 *       — the four attribute definition buckets.</li>
 *   <li><b>Value side</b>:
 *       {@code data.dataSets[*].attributes} (dataset-level values) and
 *       {@code data.dataSets[*].dimensionGroupAttributes} (group-level values).</li>
 * </ul>
 *
 * <p>SDMX 3.0 DSDs can declare {@code metadataAttributeUsages} that route to
 * any of those four buckets depending on the usage's relationship:
 * {@code "none"} → dataset, {@code "observation"} → observation,
 * {@code "dimensions": [...]} with a proper subset of non-time dims →
 * dimensionGroup, full set → series. sdmx-core's {@code DataStructureBean}
 * model has no slot for {@code metadataAttributeUsages} (IMF WEO declares 39
 * of them across all four levels), so the DSD-iterating data writer drops
 * the definitions and may misroute the values that survive runtime
 * registration. Teaching the writer about a model sdmx-core doesn't have
 * would fork the library — instead this preserver captures the upstream
 * wire bytes once and writes them back over the converted output's same
 * sections.
 *
 * <p>JSON-to-JSON 2.0 path only. Modeled on
 * {@link com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver}
 * (design 027), which solves the analogous problem on the structure endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataAttributesPreserver {

    private static final String DATA = "data";
    private static final String DATA_SETS = "dataSets";
    private static final String STRUCTURES = "structures";
    private static final String ATTRIBUTES = "attributes";
    private static final String DIMENSION_GROUP_ATTRIBUTES = "dimensionGroupAttributes";

    private final ObjectMapper objectMapper;

    public boolean isEnabled(List<FixtureConfiguration<DataFixtureType>> fixtures) {
        if (fixtures == null) {
            return false;
        }
        return fixtures.stream().anyMatch(f -> f.getType() == DataFixtureType.PRESERVE_METADATA_ATTRIBUTES);
    }

    /**
     * Captures the upstream's per-structure {@code attributes} object and per-dataset
     * {@code attributes} / {@code dimensionGroupAttributes}. Returns an empty snapshot
     * on parse failure or non-JSON payload (best-effort).
     */
    public Snapshot capture(byte[] rawJson) {
        Snapshot snap = new Snapshot();
        if (rawJson == null || rawJson.length == 0) {
            return snap;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode structures = root.path(DATA).path(STRUCTURES);
            if (structures.isArray()) {
                for (JsonNode structure : structures) {
                    JsonNode attrs = structure.path(ATTRIBUTES);
                    snap.structureAttributes.add(attrs.isObject() ? attrs.deepCopy() : null);
                }
            }
            JsonNode dataSets = root.path(DATA).path(DATA_SETS);
            if (dataSets.isArray()) {
                for (JsonNode dataset : dataSets) {
                    DatasetValues v = new DatasetValues();
                    JsonNode datasetAttrs = dataset.path(ATTRIBUTES);
                    if (datasetAttrs.isArray()) {
                        v.attributes = datasetAttrs.deepCopy();
                    }
                    JsonNode dga = dataset.path(DIMENSION_GROUP_ATTRIBUTES);
                    if (dga.isObject()) {
                        v.dimensionGroupAttributes = dga.deepCopy();
                    }
                    snap.datasetValues.add(v);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to capture metadata-attribute sections from raw JSON; preservation skipped", e);
        }
        return snap;
    }

    /**
     * Re-injects captured sections onto the converted JSON. Indexes line up with
     * the original structures/dataSets order — the conversion path is expected
     * to preserve that ordering, which it does today (single registry response,
     * one-to-one stream copy).
     */
    public byte[] inject(byte[] convertedJson, Snapshot snap) {
        if (snap == null || snap.isEmpty() || convertedJson == null || convertedJson.length == 0) {
            return convertedJson;
        }
        try {
            JsonNode root = objectMapper.readTree(convertedJson);
            boolean changed = false;

            JsonNode structures = root.path(DATA).path(STRUCTURES);
            if (structures.isArray()) {
                int i = 0;
                for (JsonNode structure : structures) {
                    if (i >= snap.structureAttributes.size()) {
                        break;
                    }
                    JsonNode captured = snap.structureAttributes.get(i);
                    if (structure.isObject() && captured != null && captured.isObject()) {
                        ((ObjectNode) structure).set(ATTRIBUTES, captured.deepCopy());
                        changed = true;
                    }
                    i++;
                }
            }

            JsonNode dataSets = root.path(DATA).path(DATA_SETS);
            if (dataSets.isArray()) {
                int i = 0;
                for (JsonNode dataset : dataSets) {
                    if (i >= snap.datasetValues.size()) {
                        break;
                    }
                    DatasetValues captured = snap.datasetValues.get(i);
                    if (dataset.isObject() && captured.hasAny()) {
                        ObjectNode datasetNode = (ObjectNode) dataset;
                        if (captured.attributes != null) {
                            datasetNode.set(ATTRIBUTES, captured.attributes.deepCopy());
                        }
                        if (captured.dimensionGroupAttributes != null) {
                            datasetNode.set(DIMENSION_GROUP_ATTRIBUTES, captured.dimensionGroupAttributes.deepCopy());
                        }
                        changed = true;
                    }
                    i++;
                }
            }

            if (!changed) {
                return convertedJson;
            }
            log.debug("Restored metadata-attribute sections on converted data response");
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException e) {
            log.warn("Failed to re-inject metadata-attribute sections into converted JSON; returning unchanged output", e);
            return convertedJson;
        }
    }

    /**
     * Captured slice. Per-structure {@code attributes} object and per-dataset
     * {@code attributes} / {@code dimensionGroupAttributes} held side-by-side so
     * indices stay aligned with the upstream's original ordering.
     */
    public static final class Snapshot {
        final List<JsonNode> structureAttributes = new ArrayList<>();
        final List<DatasetValues> datasetValues = new ArrayList<>();

        public boolean isEmpty() {
            return structureAttributes.isEmpty() && datasetValues.isEmpty();
        }
    }

    private static final class DatasetValues {
        JsonNode attributes;
        JsonNode dimensionGroupAttributes;

        boolean hasAny() {
            return attributes != null || dimensionGroupAttributes != null;
        }
    }
}
