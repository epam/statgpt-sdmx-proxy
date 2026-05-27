package com.epam.sdmxproxy.services.sdmxsource;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.api.sdmx.model.superbeans.base.ComponentSuperBean;
import io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonFlatDataWriterV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SDMX-JSON 2.0 flat-mode data writer. Wraps the sdmx-core
 * {@link SdmxJsonFlatDataWriterV2} and applies:
 *
 * <ul>
 *   <li>{@link SdmxJsonV2WriterOverrides#writeComponent} / {@code writeCode}
 *       fixes (roles plural, non-coded TIME_PERIOD shape).</li>
 *   <li>The structure-side four-bucket attribute split (issue #83 / design 030):
 *       {@code attributes.dimensionGroup} is emitted as its own array instead of
 *       being folded into {@code attributes.series}. The value-side
 *       {@code dimensionGroupAttributes} object has no equivalent in flat mode
 *       (no series keyables) — the structure sidecar is still required, so the
 *       split lives here too.</li>
 * </ul>
 */
public class CustomSdmxJsonFlatDataWriterV2 extends SdmxJsonFlatDataWriterV2 {

    public CustomSdmxJsonFlatDataWriterV2(JsonGenerator jsonGenerator, HeaderBean header, SdmxSuperBeanRetrievalManager superBeanRetrievalManager) {
        super(jsonGenerator, header, superBeanRetrievalManager);
    }

    @Override
    protected void writeAttributes() throws JsonGenerationException, IOException {
        jsonGenerator.writeObjectFieldStart("attributes");

        // Flat mode has no series keyables and emits all observations as flat arrays.
        // Structure sidecar still needs the four-bucket split. MSD-derived
        // metadata-attribute usages, where present, are restored post-conversion
        // by DimensionGroupAttributesPreserver — the writer stays within the DSD
        // bean model.
        writeStructureAttributeBucket("dataset", dsd.getDatasetAttributes(), null);
        writeStructureAttributeBucket("dimensionGroup", collectDimensionGroupAttributes(), null);
        writeStructureAttributeBucket("series", trueSeriesAttributes(),
                currentDatasetInfo.getAdditionalSeriesAttributes());
        writeStructureAttributeBucket("observation", dsd.getObservationAttributes(dimensionAtObservation),
                currentDatasetInfo.getAdditionalObsAttributes());

        jsonGenerator.writeEndObject();
    }

    private List<AttributeBean> collectDimensionGroupAttributes() {
        List<AttributeBean> result = new ArrayList<>();
        List<AttributeBean> formal = dsd.getGroupAttributes();
        if (formal != null) {
            result.addAll(formal);
        }
        Set<String> nonTime = nonTimeNonObsDimensionIds();
        for (AttributeBean attr : dsd.getSeriesAttributes(dimensionAtObservation)) {
            if (isPartialDimensionGroupAttribute(attr, nonTime)) {
                result.add(attr);
            }
        }
        return result;
    }

    private List<AttributeBean> trueSeriesAttributes() {
        Set<String> nonTime = nonTimeNonObsDimensionIds();
        List<AttributeBean> result = new ArrayList<>();
        for (AttributeBean attr : dsd.getSeriesAttributes(dimensionAtObservation)) {
            if (!isPartialDimensionGroupAttribute(attr, nonTime)) {
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
