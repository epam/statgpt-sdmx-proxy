package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.builder.IBeansBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.core.sdmx.api.factory.structure.StructureReaderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonV2StructureReaderFactory implements StructureReaderFactory {
    private final CustomSdmxJsonStructureReaderManagerV2 reader;

    @Override
    public SdmxBeans getSdmxBeans(ReadableDataLocation readableDataLocation, IBeansBuilder iBeansBuilder) {
        if (reader == null) {
            return null;
        }
        return reader.readJson(readableDataLocation, null);
    }

    @Override
    public Integer getPriority() {
        return 201;
    }
}
