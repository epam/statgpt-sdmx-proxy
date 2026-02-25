package com.epam.sdmxproxy.services.adapter.conversion;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.jsdmx.json20.structure.writer.JsonWriterFactory;
import com.epam.jsdmx.serializer.common.StubDataStructureLocalRepresentationAdapter;
import com.epam.jsdmx.serializer.sdmx30.common.DefaultReferenceAdapter;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.mapping.StructureMapperImpl;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.services.sdmxsource.JsonV1StructureReaderFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonV2StructureReaderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.builder.IBeansBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.core.sdmx.api.factory.structure.StructureReaderFactory;
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
 * Service for converting availability data between different formats.
 * Availability queries return constraint metadata (ContentConstraintBean/DataConstraint)
 * which are structural metadata, so we can use the same parsers and converters as for structures.
 * <p>
 * According to SDMX REST API 3.0 specification:
 * - JSON: application/vnd.sdmx.structure+json;version=2.0.0 (default)
 * - XML: application/vnd.sdmx.structure+xml;version=3.0.0
 * <p>
 * MVP scope: Only JSON format for SDMX 3.0 is supported.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingAvailabilityConversionService {

    private final ObjectMapper objectMapper;
    private final SdmxSourceReadableDataLocationFactory sdmxSourceReadableDataLocationFactory;
    private final SdmxMLStructureReaderFactory sdmxMLStructureReaderFactory;
    private final JsonV1StructureReaderFactory jsonV1StructureReaderFactory;
    private final JsonV2StructureReaderFactory jsonV2StructureReaderFactory;
    private final IBeansBuilder iBeansBuilder;

    /**
     * Converts availability data from one format to another.
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
        if (isJsonMediaType(targetMediaType)) {
            processAsJson(inputStream, outputStream, sourceFormat, targetMediaType);
            return;
        }

        if (isXmlMediaType(targetMediaType)) {
            throw new UnsupportedOperationException(
                    String.format("Availability conversion to XML format (%s) is not supported in MVP", targetMediaType)
            );
        }

        throw new UnsupportedOperationException(
                String.format("Availability conversion to %s is not supported", targetMediaType)
        );
    }

    private boolean isJsonMediaType(MediaType mediaType) {
        return mediaType.getSubtype().contains("json");
    }

    private boolean isXmlMediaType(MediaType mediaType) {
        return mediaType.getSubtype().contains("xml");
    }

    private void processAsJson(
            InputStream inputStream,
            OutputStream outputStream,
            ReturnFormat sourceFormat,
            MediaType targetMediaType
    ) {
        SdmxBeans sdmxBeans = parseAvailability(inputStream, sourceFormat);

        SdmxVersion extractedVersion = SdmxMediaType.extractSdmxVersion(targetMediaType.toString());

        switch (extractedVersion) {
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
            case SDMX_2_1 -> {
                throw new UnsupportedOperationException(
                        String.format("Availability conversion for SDMX 2.1 is not supported in MVP")
                );
            }
            default -> throw new UnsupportedOperationException(
                    String.format("Unsupported SDMX version for availability conversion: %s", extractedVersion)
            );
        }
    }

    public SdmxBeans parseAvailability(InputStream inputStream, ReturnFormat registryFormat) {
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


    private Artefacts getArtefacts(SdmxBeans sdmxBeans) {
        StructureMapperImpl structureMapper = new StructureMapperImpl();
        return structureMapper.map(sdmxBeans);
    }
}
