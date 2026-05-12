package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import org.springframework.stereotype.Service;

@Service
public class JsonDataWriterFactoryProducer {

    public CustomSdmxJsonDataWriterFactory getDataWriterFactory(
            SdmxBeanRetrievalManager beanRetrievalManager
    ) {
        return new CustomSdmxJsonDataWriterFactory(new CustomSdmxSuperBeanRetrievalManagerImpl(beanRetrievalManager), beanRetrievalManager);
    }


}