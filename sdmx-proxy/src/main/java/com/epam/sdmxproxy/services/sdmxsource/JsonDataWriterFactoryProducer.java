package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.core.sdmx.manager.structure.SdmxSuperBeanRetrievalManagerImpl;
import org.springframework.stereotype.Service;

@Service
public class JsonDataWriterFactoryProducer {

    public CustomSdmxJsonDataWriterFactory getDataWriterFactory(
            SdmxBeanRetrievalManager beanRetrievalManager
    ) {
        return new CustomSdmxJsonDataWriterFactory(new SdmxSuperBeanRetrievalManagerImpl(beanRetrievalManager), beanRetrievalManager);
    }


}