package com.epam.sdmxproxy.services.sdmxsource;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.api.sdmx.model.superbeans.base.ComponentSuperBean;
import io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonFlatDataWriterV2;

import java.io.IOException;

/**
 * SDMX-JSON 2.0 flat-mode data writer with the
 * {@link SdmxJsonV2WriterOverrides} corrections applied. See that class for
 * the override rationale.
 */
public class CustomSdmxJsonFlatDataWriterV2 extends SdmxJsonFlatDataWriterV2 {

    public CustomSdmxJsonFlatDataWriterV2(JsonGenerator jsonGenerator, HeaderBean header, SdmxSuperBeanRetrievalManager superBeanRetrievalManager) {
        super(jsonGenerator, header, superBeanRetrievalManager);
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
