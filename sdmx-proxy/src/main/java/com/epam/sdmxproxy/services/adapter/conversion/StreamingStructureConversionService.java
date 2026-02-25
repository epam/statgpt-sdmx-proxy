package com.epam.sdmxproxy.services.adapter.conversion;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.jsdmx.json20.structure.writer.JsonWriterFactory;
import com.epam.jsdmx.serializer.common.StubDataStructureLocalRepresentationAdapter;
import com.epam.jsdmx.serializer.sdmx30.common.DefaultReferenceAdapter;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.mapping.StructureMapperImpl;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxMLStructureWriterFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonV1StructureReaderFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonV2StructureReaderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.builder.IBeansBuilder;
import io.sdmx.api.sdmx.constants.STRUCTURE_OUTPUT_FORMAT;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.core.sdmx.api.factory.structure.StructureReaderFactory;
import io.sdmx.core.sdmx.format.SdmxStructureFormat;
import io.sdmx.format.ml.factory.structure.SdmxMLStructureReaderFactory;
import io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Service for converting structure data between different formats (XML ↔ JSON).
 * Supports streaming conversion for large structures without materializing them in memory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingStructureConversionService {

    private final ObjectMapper objectMapper;
    private final SdmxSourceReadableDataLocationFactory sdmxSourceReadableDataLocationFactory;
    private final CustomSdmxMLStructureWriterFactory sdmxMLStructureWriterFactory;
    private final SdmxMLStructureReaderFactory sdmxMLStructureReaderFactory;
    private final JsonV1StructureReaderFactory jsonV1StructureReaderFactory;
    private final JsonV2StructureReaderFactory jsonV2StructureReaderFactory;
    private final IBeansBuilder iBeansBuilder;
    /**
     * Converts structure data from one format to another.
     *
     * @param inputStream     input stream in sourceFormat
     * @param outputStream    output stream in targetFormat
     * @param sourceFormat    source format (registry format)
     * @param targetMediaType target media type (requested format)
     */
    public void convert(
            InputStream inputStream,
            OutputStream outputStream,
            ReturnFormat sourceFormat,
            MediaType targetMediaType
    ) {
        SdmxBeans sdmxBeans = parseStructures(inputStream, sourceFormat);
        convert(sdmxBeans, outputStream, targetMediaType);
    }

    public void convert(SdmxBeans sdmxBeans, OutputStream outputStream, MediaType targetMediaType) {
        if (isJsonMediaType(targetMediaType)) {
            writeAsJson(sdmxBeans, outputStream, targetMediaType);
            return;
        }

        if (isXmlMediaType(targetMediaType)) {
            writeAsXml(sdmxBeans, outputStream, targetMediaType);
            return;
        }

        throw new UnsupportedOperationException(
                String.format("Structure conversion to %s is not supported", targetMediaType)
        );
    }

    private boolean isJsonMediaType(MediaType mediaType) {
        return mediaType.getSubtype().contains("json");
    }

    private boolean isXmlMediaType(MediaType mediaType) {
        return mediaType.getSubtype().contains("xml");
    }

    public SdmxBeans parseStructures(InputStream inputStream, ReturnFormat registryFormat) {
        ReadableDataLocation location = sdmxSourceReadableDataLocationFactory.getReadableDataLocation(inputStream);
        StructureReaderFactory readerFactory = getParserFactory(registryFormat);
        return readerFactory.getSdmxBeans(location, iBeansBuilder);
    }

    private StructureReaderFactory getParserFactory(ReturnFormat returnFormat) {
        return switch (returnFormat) {
            case XML_2_1 -> sdmxMLStructureReaderFactory;
            case JSON_1_0_0 -> jsonV1StructureReaderFactory;
            case JSON_STRUCTURE_2_0_0 -> jsonV2StructureReaderFactory;
            default ->
                    throw new UnsupportedOperationException("Unsupported return format for parsing: " + returnFormat);
        };
    }

    private void writeAsJson(SdmxBeans sdmxBeans, OutputStream outputStream, MediaType targetMediaType) {

        switch (SdmxMediaType.extractSdmxVersion(targetMediaType.toString())) {
            case SDMX_2_1 -> {
                throw new UnsupportedOperationException("Json for SDMX 2.1 (JSON v1.0) is not currently supported. Choose different format");
            }
            case SDMX_3_0 -> {
                Artefacts artefacts = getArtefacts(sdmxBeans);
                JsonWriterFactory writerFactory = new JsonWriterFactory(
                        objectMapper,
                        List.of(),
                        new DefaultReferenceAdapter(),
                        new StubDataStructureLocalRepresentationAdapter()
                );
                writerFactory.newInstance(outputStream).write(artefacts);
            }
            default -> throw new RuntimeException("Unsupported SDMX version for JSON conversion");
        }
    }

    private Artefacts getArtefacts(SdmxBeans sdmxBeans) {
        StructureMapperImpl structureMapper = new StructureMapperImpl();
        return structureMapper.map(sdmxBeans);
    }

    private void writeAsXml(SdmxBeans sdmxBeans, OutputStream outputStream, MediaType targetMediaType) {
        switch (SdmxMediaType.extractSdmxVersion(targetMediaType.toString())) {
            case SDMX_2_1 -> {
                //TODO write a better mapping of targetMediaType onto STRUCTURE_OUTPUT_FORMAT
                SdmxStructureFormat xmlV21 = new SdmxStructureFormat(STRUCTURE_OUTPUT_FORMAT.SDMX_V21_STRUCTURE_DOCUMENT);
                sdmxMLStructureWriterFactory.getStructureWriterEngine(xmlV21).writeStructures(sdmxBeans, null, outputStream);
            }
            case SDMX_3_0 -> {
                throw new UnsupportedOperationException("XML for SDMX 3.0 is not currently supported. Choose different format");
            }
            default -> throw new RuntimeException("Unsupported SDMX version for JSON conversion");
        }
    }
}
