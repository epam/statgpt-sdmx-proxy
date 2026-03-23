package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.constants.DATASET_ACTION;
import io.sdmx.api.sdmx.constants.SDMX_SCHEMA;
import io.sdmx.api.sdmx.manager.output.SchemaLocationManager;
import io.sdmx.api.sdmx.manager.retrieval.HeaderRetrievalManager;
import io.sdmx.format.ml.constant.StaxStructureWriterUtil;
import io.sdmx.utils.core.application.SingletonStore;
import io.sdmx.utils.core.xml.StaxWriter;
import org.springframework.stereotype.Service;

@Service
public class CustomStaxStructureWriterEngineV21 extends CustomStaxAbstractStructureWriterEngineV21 {

    public CustomStaxStructureWriterEngineV21() {
        super(SDMX_SCHEMA.VERSION_TWO_POINT_ONE,
                StaxStructureWriterUtil.MESSAGE_NS,
                StaxStructureWriterUtil.STRCUTURE_NS,
                StaxStructureWriterUtil.COMMON_NS,
                StaxStructureWriterUtil.REGISTRY_NS,
                SingletonStore.getSingleton(SchemaLocationManager.class, false),
                SingletonStore.getSingleton(HeaderRetrievalManager.class, false));
    }

    @Override
    protected String getDocumentRoot() {
        return "Structure";
    }

    @Override
    protected void afterHeader(StaxWriter writer, DATASET_ACTION action) {
        //Do nothing
    }


}

