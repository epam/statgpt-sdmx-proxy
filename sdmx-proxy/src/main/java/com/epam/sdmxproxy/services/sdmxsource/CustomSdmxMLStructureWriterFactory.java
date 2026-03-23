package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.model.format.StructureFormat;
import io.sdmx.core.sdmx.api.engine.structure.StructureWriterEngine;
import io.sdmx.core.sdmx.api.factory.structure.StructureWriterFactory;
import io.sdmx.core.sdmx.format.SdmxStructureFormat;
import io.sdmx.format.ml.engine.structure.writer.v1.StaxStructureWriterEngineV1;
import io.sdmx.format.ml.engine.structure.writer.v2.StaxStructureWriterEngineV2;
import io.sdmx.format.ml.engine.structure.writer.v21.StaxRegistrySubmitWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v3.StaxStructureWriterEngineV3;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomSdmxMLStructureWriterFactory implements StructureWriterFactory {
    private final CustomStaxStructureWriterEngineV21 staxStructureWriterEngineV21;


    @Override
    public StructureWriterEngine getStructureWriterEngine(StructureFormat outputFormat) {
        if (outputFormat instanceof SdmxStructureFormat) {
            SdmxStructureFormat sdmxStructureFormat = (SdmxStructureFormat) outputFormat;
            boolean prettyPrint = sdmxStructureFormat.isPrettyPrint();
            switch (outputFormat.getSdmxOutputFormat()) {
                case SDMX_V1_STRUCTURE_DOCUMENT:
                    return StaxStructureWriterEngineV1.getInstance(prettyPrint);
                case SDMX_V21_QUERY_RESPONSE_DOCUMENT:
                    break;
                case SDMX_V21_REGISTRY_SUBMIT_DOCUMENT:
                    return StaxRegistrySubmitWriterEngineV21.getInstance(prettyPrint);
                case SDMX_V21_STRUCTURE_DOCUMENT:
                    return staxStructureWriterEngineV21;
                case SDMX_V2_REGISTRY_QUERY_RESPONSE_DOCUMENT:
                    break;
                case SDMX_V2_REGISTRY_SUBMIT_DOCUMENT:
                    break;
                case SDMX_V2_STRUCTURE_DOCUMENT:
                    return StaxStructureWriterEngineV2.getInstance(prettyPrint);
                case SDMX_V3_STRUCTURE_DOCUMENT:
                    return StaxStructureWriterEngineV3.getInstance(prettyPrint);
                default:
                    break;
            }
        }
        return null;
    }

    @Override
    public Integer getPriority() {
        return 11;
    }
}
