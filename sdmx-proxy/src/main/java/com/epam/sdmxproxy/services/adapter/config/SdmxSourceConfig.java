package com.epam.sdmxproxy.services.adapter.config;

import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxCsvDataReaderFactoryV2;
import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxJsonDataReaderFactory;
import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxJsonStructureReaderManagerV2;
import io.sdmx.format.csv.factory.v1.SdmxCsvDataReaderFactoryV1;
import io.sdmx.format.json.factory.data.SdmxJsonDataWriterFactory;
import io.sdmx.format.json.manager.SdmxJsonStructureReaderManagerV1;
import io.sdmx.format.ml.factory.data.SdmxMLDataReaderFactory;
import io.sdmx.format.ml.factory.structure.SdmxMLStructureReaderFactory;
import io.sdmx.im.beans.builder.V3BeansBuilder;
import io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SdmxSourceConfig {

    @Bean
    public SdmxMLStructureReaderFactory sdmxMLStructureReaderFactory() {
        return SdmxMLStructureReaderFactory.getInstance();
    }

    @Bean
    public SdmxJsonStructureReaderManagerV1 sdmxJsonStructureReaderManagerV1() {
        return SdmxJsonStructureReaderManagerV1.getInstance();
    }

    @Bean
    public CustomSdmxJsonStructureReaderManagerV2 customSdmxJsonStructureReaderManagerV2() {
        return new CustomSdmxJsonStructureReaderManagerV2();
    }

    @Bean
    public SdmxSourceReadableDataLocationFactory sdmxSourceReadableDataLocationFactory() {
        return new SdmxSourceReadableDataLocationFactory();
    }

    @Bean
    public CustomSdmxJsonDataReaderFactory sdmxJsonDataReaderFactory() {
        return CustomSdmxJsonDataReaderFactory.getInstance();
    }

    @Bean
    public SdmxMLDataReaderFactory sdmxMLDataReaderFactory() {
        return SdmxMLDataReaderFactory.getInstance();
    }

    @Bean
    public SdmxCsvDataReaderFactoryV1 sdmxCsvDataReaderFactoryV1() {
        return SdmxCsvDataReaderFactoryV1.getInstance();
    }

    @Bean
    public CustomSdmxCsvDataReaderFactoryV2 sdmxCsvDataReaderFactoryV2() {
        return CustomSdmxCsvDataReaderFactoryV2.getInstance();
    }

    @Bean
    public SdmxJsonDataWriterFactory sdmxJsonDataWriterFactory() {
        return SdmxJsonDataWriterFactory.getInstance();
    }

    @Bean
    public V3BeansBuilder v3BeansBuilder() {
        return new V3BeansBuilder(null);
    }

}
