package com.epam.sdmxproxy.services.sdmxsource;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import io.sdmx.api.collection.KeyValue;
import io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.IDatasetStructures;
import io.sdmx.api.sdmx.model.beans.base.AnnotationBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.data.IDatasetAttributes;
import io.sdmx.api.sdmx.model.data.Keyable;
import io.sdmx.api.sdmx.model.header.DatasetHeaderBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.api.sdmx.model.superbeans.base.ComponentSuperBean;
import io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonSeriesDataWriterV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * SDMX-JSON 2.0 series-mode data writer. Wraps the sdmx-core
 * {@link SdmxJsonSeriesDataWriterV2} and adds the corrections required by issue
 * #83 / design 030:
 *
 * <ol>
 *   <li><b>Value-side native group emission.</b> {@link #writeKey} intercepts
 *       Group {@link Keyable}s (which the parent silently drops) and buffers
 *       them per partial-dimension-value-combination key.
 *       {@link #writeDatasetAttributes} flushes the buffer as
 *       {@code data.dataSets[*].dimensionGroupAttributes} before the dataset
 *       object closes. Pairs with removing the
 *       {@code GroupDataWriterEngine} wrapper in
 *       {@link CustomSdmxJsonDataWriterFactory} for SDMX-JSON 2.0.</li>
 *   <li><b>Structure-side four-bucket split.</b> {@link #writeAttributes()}
 *       replaces the parent's {@code attributes.series} folding (which mixed
 *       group-attached attribute definitions into the series array) with a
 *       proper four-bucket emission: {@code dataset}, {@code dimensionGroup},
 *       {@code series}, {@code observation}.</li>
 * </ol>
 *
 * The writer only knows about attributes declared in the DSD's bean model.
 * SDMX 3.0 dataflows can expose additional attributes sourced from a
 * MetadataStructureDefinition (IMF WEO: {@code BASE_YEAR},
 * {@code METHODOLOGY_NOTES}, ...) — sdmx-core's {@code DataStructureBean} has
 * no slot for those, so they would be dropped here. They are preserved by the
 * post-conversion {@code DimensionGroupAttributesPreserver} fixture instead,
 * which captures the upstream's {@code attributes.dimensionGroup} and
 * {@code dimensionGroupAttributes} sections from the raw response and
 * re-injects them onto the converted output. This writer deliberately stays
 * within the bean model so future sdmx-core upgrades drop in cleanly.
 *
 * <p>The {@link SdmxJsonV2WriterOverrides#writeComponent} / {@code writeCode}
 * fixes (roles plural, non-coded TIME_PERIOD shape) are inherited from the
 * existing overrides and applied per attribute via the writer's component hooks.
 */
public class CustomSdmxJsonSeriesDataWriterV2 extends SdmxJsonSeriesDataWriterV2 {

    /**
     * Buffer of Group keyables encountered during the dataset. The map is keyed by the
     * group's dimension <i>codes</i> joined by a NUL byte (a stable, value-table-
     * independent identity that coalesces duplicate groups). The encoded
     * partial-dimension-value-combination string used in the JSON output is computed
     * lazily at flush time (in {@link #writeDimensionGroupAttributes}), once every
     * series has been written and {@code currentDatasetInfo}'s reported-value tables
     * are fully populated.
     */
    private final Map<String, BufferedGroup> groupAttrBuffer = new LinkedHashMap<>();

    private record BufferedGroup(List<KeyValue> dimensions, List<KeyValue> attributes) {
    }

    public CustomSdmxJsonSeriesDataWriterV2(JsonGenerator jsonGenerator, HeaderBean header, SdmxSuperBeanRetrievalManager superBeanRetrievalManager) {
        super(jsonGenerator, header, superBeanRetrievalManager);
    }

    @Override
    public void startDataset(IDatasetStructures structures, DatasetHeaderBean header, IDatasetAttributes datasetAttributes, AnnotationBean... annotations) {
        groupAttrBuffer.clear();
        super.startDataset(structures, header, datasetAttributes, annotations);
    }

    @Override
    public void writeKey(Keyable key) {
        if (key != null && !key.isSeries()) {
            // Register the group's dimension values (and attribute concepts) with the
            // dataset's value tables so that getReportedIndex returns distinct indices
            // at flush time. Without this, orphan groups (whose dims are not covered
            // by any series in the dataset) all collapse to the same encoded key and
            // overwrite each other in the output JSON object.
            currentDatasetInfo.writeKey(key);
            String bufferKey = identityKey(key);
            groupAttrBuffer.put(bufferKey, new BufferedGroup(
                    new ArrayList<>(key.getKey()),
                    new ArrayList<>(key.getAttributes())));
            return;
        }
        super.writeKey(key);
    }

    /**
     * Stable buffer-map key derived from the group's dimension codes. NUL-separated to
     * avoid collisions with codes that contain ':' or other delimiters. This is
     * independent of the writer's value-table indexing, so it can be computed
     * eagerly at writeKey time (the value table is not yet populated then).
     */
    private static String identityKey(Keyable key) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue kv : key.getKey()) {
            sb.append(kv.getConcept()).append('=').append(kv.getCode()).append('\0');
        }
        return sb.toString();
    }

    @Override
    protected void writeDatasetAttributes() throws IOException {
        super.writeDatasetAttributes();
        writeDimensionGroupAttributes();
    }

    private void writeDimensionGroupAttributes() throws IOException {
        if (groupAttrBuffer.isEmpty()) {
            return;
        }
        List<AttributeBean> groupAttrs = collectDimensionGroupAttributes();
        if (groupAttrs.isEmpty()) {
            return;
        }
        jsonGenerator.writeObjectFieldStart("dimensionGroupAttributes");
        for (Entry<String, BufferedGroup> entry : groupAttrBuffer.entrySet()) {
            BufferedGroup group = entry.getValue();
            String encoded = encodeGroupKeyFromValues(group.dimensions());
            jsonGenerator.writeArrayFieldStart(encoded);
            writeAttributes(groupAttrs, group.attributes());
            jsonGenerator.writeEndArray();
        }
        jsonGenerator.writeEndObject();
    }

    /**
     * Collects attributes whose attachment level corresponds to SDMX-JSON 2.0
     * {@code dimensionGroup} bucket. This is the union of:
     *
     * <ul>
     *   <li>Attributes attached to a formal DSD {@code <Group>}
     *       ({@code dsd.getGroupAttributes()}).</li>
     *   <li>Attributes whose {@code AttributeRelationship.dimensions} is a
     *       <i>proper subset</i> of the DSD's non-time dimensions — the SDMX 3.0
     *       partial-dimension-group form used by IMF, Eurostat, and others.
     *       sdmx-core reports these via {@code dsd.getSeriesAttributes(dimAtObs)}
     *       lumped with the true series-level attrs; we split them back out
     *       here.</li>
     * </ul>
     */
    private List<AttributeBean> collectDimensionGroupAttributes() {
        List<AttributeBean> result = new ArrayList<>();
        List<AttributeBean> formalGroupAttrs = dsd.getGroupAttributes();
        if (formalGroupAttrs != null) {
            result.addAll(formalGroupAttrs);
        }
        Set<String> nonTimeDimIds = nonTimeNonObsDimensionIds();
        for (AttributeBean attr : dsd.getSeriesAttributes(dimensionAtObservation)) {
            if (isPartialDimensionGroupAttribute(attr, nonTimeDimIds)) {
                result.add(attr);
            }
        }
        return result;
    }

    /**
     * @return the series-level attributes that should go into the {@code attributes.series}
     *     bucket of the structure sidecar — i.e. {@code getSeriesAttributes(dimAtObs)}
     *     minus the partial-dimension-group attributes.
     */
    private List<AttributeBean> trueSeriesAttributes() {
        Set<String> nonTimeDimIds = nonTimeNonObsDimensionIds();
        List<AttributeBean> result = new ArrayList<>();
        for (AttributeBean attr : dsd.getSeriesAttributes(dimensionAtObservation)) {
            if (!isPartialDimensionGroupAttribute(attr, nonTimeDimIds)) {
                result.add(attr);
            }
        }
        return result;
    }

    private Set<String> nonTimeNonObsDimensionIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (DimensionBean dim : dsd.getDimensions()) {
            if (dim.isTimeDimension() || dim.getId().equals(dimensionAtObservation)) {
                continue;
            }
            ids.add(dim.getId());
        }
        return ids;
    }

    /**
     * An attribute is a partial-dimension-group attribute when its
     * {@code AttributeRelationship.dimensions} is a non-empty <i>proper subset</i>
     * of the DSD's non-time, non-observation dimensions. Attributes whose
     * dimension list equals the full non-time set are series-level (the SDMX-JSON
     * 2.0 {@code attributes.series} bucket).
     */
    private static boolean isPartialDimensionGroupAttribute(AttributeBean attr, Set<String> nonTimeDimIds) {
        List<String> refs = attr.getDimensionReferences();
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        if (refs.size() >= nonTimeDimIds.size()) {
            return false;
        }
        return new HashSet<>(nonTimeDimIds).containsAll(refs);
    }

    /**
     * Encodes a Group's dimensions into the SDMX-JSON 2.0
     * partial-dimension-value-combination string (e.g. {@code ":0::"}). Called at
     * flush time when {@code currentDatasetInfo}'s value tables are populated.
     *
     * <p>Per the SDMX-JSON 2.0 field guide, the key spans <i>all</i> DSD
     * dimensions (those in {@code structure.dimensions.series} and the
     * observation dimension in {@code structure.dimensions.observation}). Each
     * position holds the value's index in the corresponding {@code values}
     * array, or empty when the dimension is not part of the group. The
     * time/observation dimension position is always empty for groups, since
     * group attributes cannot attach at the observation level.
     */
    private String encodeGroupKeyFromValues(List<KeyValue> groupDimensions) {
        Map<String, KeyValue> kvByConcept = new HashMap<>();
        for (KeyValue kv : groupDimensions) {
            kvByConcept.put(kv.getConcept(), kv);
        }
        List<DimensionBean> dims = dsd.getDimensions();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (DimensionBean dim : dims) {
            if (!first) {
                sb.append(':');
            }
            first = false;
            KeyValue kv = kvByConcept.get(dim.getId());
            if (kv != null && !dim.isTimeDimension() && !dim.getId().equals(dimensionAtObservation)) {
                int idx = currentDatasetInfo.getReportedIndex(dim.getId(), kv.getCode());
                sb.append(idx);
            }
            // else: leave the position empty (group does not span this dimension,
            // or this is the time/observation dimension which never carries a
            // group value).
        }
        return sb.toString();
    }

    @Override
    protected void writeAttributes() throws JsonGenerationException, IOException {
        jsonGenerator.writeObjectFieldStart("attributes");

        writeStructureAttributeBucket("dataset", dsd.getDatasetAttributes(), null);
        writeStructureAttributeBucket("dimensionGroup", collectDimensionGroupAttributes(), null);
        writeStructureAttributeBucket("series", trueSeriesAttributes(),
                currentDatasetInfo.getAdditionalSeriesAttributes());
        writeStructureAttributeBucket("observation", dsd.getObservationAttributes(dimensionAtObservation),
                currentDatasetInfo.getAdditionalObsAttributes());

        jsonGenerator.writeEndObject();
    }

    private void writeStructureAttributeBucket(String fieldName, List<AttributeBean> attrs, List<String> additional) throws IOException {
        jsonGenerator.writeArrayFieldStart(fieldName);
        if (attrs != null) {
            for (AttributeBean attr : attrs) {
                ComponentSuperBean comp = currentDatasetInfo.getCurrentDSDSuperBean().getComponentById(attr.getId());
                if (comp != null) {
                    writeComponent(comp, -1);
                }
            }
        }
        if (additional != null) {
            writeAdditionalAttributes(additional);
        }
        jsonGenerator.writeEndArray();
    }

    @Override
    protected void writeComponent(ComponentSuperBean component, int position) throws JsonGenerationException, IOException {
        SdmxJsonV2WriterOverrides.writeComponent(jsonGenerator, component, position, currentDatasetInfo, includePosition());
    }

    @Override
    protected void writeCode(String code, boolean isTime, ComponentSuperBean component) throws IOException {
        SdmxJsonV2WriterOverrides.writeCode(jsonGenerator, code, isTime, component, includePosition());
    }
}
