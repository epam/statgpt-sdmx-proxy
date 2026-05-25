package com.epam.sdmxproxy.services.sdmxsource;

import com.fasterxml.jackson.core.JsonGenerator;
import io.sdmx.api.date.TIME_FORMAT;
import io.sdmx.api.sdmx.constants.ATTRIBUTE_ATTACHMENT_LEVEL;
import io.sdmx.api.sdmx.model.beans.base.ComponentBean;
import io.sdmx.api.sdmx.model.beans.base.EnumeratedItemBean;
import io.sdmx.api.sdmx.model.beans.base.EnumeratedListBean;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.reference.IDirectCrossReferenceBean;
import io.sdmx.api.sdmx.model.superbeans.base.ComponentSuperBean;
import io.sdmx.api.sdmx.model.superbeans.datastructure.AttributeSuperBean;
import io.sdmx.core.data.engine.writer.DatasetInfoDataWriterEngine;
import io.sdmx.utils.core.date.DateUtil;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.util.List;

/**
 * Shared overrides for SDMX-JSON 2.0 data writers (series and flat). Fixes
 * sdmx-core writer divergences from the SDMX-JSON 2.0 data schema:
 * <ul>
 *   <li>{@code roles} (plural array of strings) instead of {@code role}
 *       (singular string-or-null); populated from the bean's
 *       {@code conceptRole(s)} list plus {@code "time"} for time dimensions.</li>
 *   <li>Non-coded {@code TIME_PERIOD} values emit
 *       {@code {"value": "1999"}} instead of the coded
 *       {@code {"id","name","start","end"}} shape with fabricated date bounds.</li>
 * </ul>
 * Mirrors the structure of {@code AbstractJsonDataWriter.writeComponent} /
 * {@code .writeCode} (sdmx-core 2.3.9 fusion-sdmx-json) so future sdmx-core
 * upgrades that change the wire shape need to be ported here as well.
 */
@UtilityClass
class SdmxJsonV2WriterOverrides {

    static void writeComponent(
            JsonGenerator jsonGenerator,
            ComponentSuperBean component,
            int position,
            DatasetInfoDataWriterEngine currentDatasetInfo,
            boolean includePosition
    ) throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("id", component.getId());
        jsonGenerator.writeStringField("name", component.getConcept().getBuiltFrom().getName());
        if (component.getConcept().getBuiltFrom().getDescription() != null) {
            jsonGenerator.writeStringField("description", component.getConcept().getBuiltFrom().getDescription());
        }

        if (position >= 0) {
            jsonGenerator.writeNumberField("keyPosition", position);
        }

        if (component instanceof AttributeSuperBean attrSuper) {
            jsonGenerator.writeObjectFieldStart("relationship");
            AttributeBean cast = attrSuper.getBuiltFrom();
            ATTRIBUTE_ATTACHMENT_LEVEL attachmentLevel = cast.getAttachmentLevel();
            switch (attachmentLevel) {
                case DATA_SET -> {
                    jsonGenerator.writeObjectFieldStart("none");
                    jsonGenerator.writeEndObject();
                }
                case DIMENSION_GROUP -> {
                    List<String> dimensionReferences = cast.getDimensionReferences();
                    jsonGenerator.writeArrayFieldStart("dimensions");
                    for (String string : dimensionReferences) {
                        jsonGenerator.writeString(string);
                    }
                    jsonGenerator.writeEndArray();
                }
                case GROUP -> {
                    // no-op (matches base writer)
                }
                case OBSERVATION -> {
                    for (String measure : cast.getMeasureReferences()) {
                        if (measure.equals("OBS_VALUE")) {
                            jsonGenerator.writeStringField("primaryMeasure", measure);
                        }
                    }
                }
                default -> {
                    // no-op
                }
            }
            jsonGenerator.writeEndObject();
        }

        boolean isTime = component.getId().equals(DimensionBean.TIME_DIMENSION_FIXED_ID);

        ComponentBean built = (ComponentBean) component.getBuiltFrom();
        List<? extends IDirectCrossReferenceBean<ConceptBean>> conceptRoles = null;
        if (built instanceof DimensionBean dim) {
            conceptRoles = dim.getConceptRole();
        } else if (built instanceof AttributeBean attr) {
            conceptRoles = attr.getConceptRoles();
        }
        boolean hasConceptRoles = conceptRoles != null && !conceptRoles.isEmpty();
        if (isTime || hasConceptRoles) {
            jsonGenerator.writeArrayFieldStart("roles");
            if (isTime) {
                jsonGenerator.writeString("time");
            }
            if (hasConceptRoles) {
                for (IDirectCrossReferenceBean<ConceptBean> role : conceptRoles) {
                    String id = role.getReference().getFullIdentifiableId();
                    if (id != null && !id.isBlank()) {
                        jsonGenerator.writeString(id);
                    }
                }
            }
            jsonGenerator.writeEndArray();
        }

        List<String> allCodes = currentDatasetInfo.getReportedValues(component.getId());
        jsonGenerator.writeArrayFieldStart("values");
        for (String currentCode : allCodes) {
            writeCode(jsonGenerator, currentCode, isTime, component, includePosition);
        }
        jsonGenerator.writeEndArray();
        jsonGenerator.writeEndObject();
    }

    static void writeCode(
            JsonGenerator jsonGenerator,
            String code,
            boolean isTime,
            ComponentSuperBean component,
            boolean includePosition
    ) throws IOException {
        jsonGenerator.writeStartObject();
        if (isTime) {
            boolean coded = component != null && component.getCodelist(false) != null;
            if (!coded) {
                // Non-coded ObservationalTimePeriod: SDMX-JSON 2.0 schema requires {"value":"..."}.
                jsonGenerator.writeStringField("value", code);
                jsonGenerator.writeEndObject();
                return;
            }
            String start = DateUtil.formatDate(DateUtil.formatDate(code, true), TIME_FORMAT.DATE_TIME);
            String end = DateUtil.formatDate(DateUtil.formatDate(code, false), TIME_FORMAT.DATE_TIME);
            jsonGenerator.writeStringField("start", start);
            jsonGenerator.writeStringField("end", end);
            jsonGenerator.writeStringField("id", code);
            jsonGenerator.writeStringField("name", code);
            jsonGenerator.writeEndObject();
            return;
        }
        String id = code;
        String name = code;
        String description = null;
        if (component != null) {
            EnumeratedListBean<? extends EnumeratedItemBean> codelist = component.getCodelist(true);
            if (codelist != null) {
                EnumeratedItemBean codeBean = codelist.getItemById(code);
                if (codeBean != null) {
                    id = codeBean.getId();
                    name = codeBean.getName();
                    description = codeBean.getDescription();
                    if (includePosition) {
                        jsonGenerator.writeNumberField("position", codeBean.getPosition());
                    }
                }
            }
        }
        jsonGenerator.writeStringField("id", id);
        jsonGenerator.writeStringField("name", name);
        if (description != null) {
            jsonGenerator.writeStringField("description", description);
        }
        jsonGenerator.writeEndObject();
    }
}
