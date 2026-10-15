package com.epam.sdmxproxy.services.adapter.conversion;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.jsdmx.json20.structure.writer.JsonWriterFactory;
import com.epam.jsdmx.serializer.common.StubDataStructureLocalRepresentationAdapter;
import com.epam.jsdmx.serializer.sdmx30.common.DefaultReferenceAdapter;
import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.common.mapping.StructureMapperImpl;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.exception.UnsupportedConversionException;
import com.epam.sdmxproxy.services.sdmxsource.InMemoryReadableDataLocationFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonV1StructureReaderFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonV2StructureReaderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.builder.IBeansBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.core.sdmx.api.factory.structure.StructureReaderFactory;
import io.sdmx.format.ml.factory.structure.SdmxMLStructureReaderFactory;
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
    private final InMemoryReadableDataLocationFactory sdmxSourceReadableDataLocationFactory;
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
            SdmxFormat sourceFormat,
            MediaType targetMediaType
    ) {
        if (isJsonMediaType(targetMediaType)) {
            processAsJson(inputStream, outputStream, sourceFormat, targetMediaType);
            return;
        }

        if (isXmlMediaType(targetMediaType)) {
            throw new UnsupportedConversionException(
                    String.format("Availability conversion to XML format (%s) is not supported in MVP", targetMediaType)
            );
        }

        throw new UnsupportedConversionException(
                String.format("Availability conversion to %s is not supported", targetMediaType)
        );
    }

    /**
     * Writes an already-built availability artefact in {@code targetMediaType}.
     * <p>
     * Extracted from {@link #convert} so availability emulation (design 040) renders through the
     * same writer as the conversion path. The emulator's probe path has no upstream constraint
     * document to parse, so it constructs {@link Artefacts} itself and calls this directly; the
     * constraint path parses and then calls it too. One writer means the two emulation paths
     * cannot drift into producing different JSON for the same coverage.
     *
     * @param artefacts       artefact set to write; for availability, one data constraint
     * @param outputStream    target stream
     * @param targetMediaType media type requested by the client
     */
    public void write(Artefacts artefacts, OutputStream outputStream, MediaType targetMediaType) {
        if (isXmlMediaType(targetMediaType)) {
            throw new UnsupportedConversionException(
                    String.format("Availability conversion to XML format (%s) is not supported in MVP", targetMediaType)
            );
        }
        if (!isJsonMediaType(targetMediaType)) {
            throw new UnsupportedConversionException(
                    String.format("Availability conversion to %s is not supported", targetMediaType)
            );
        }

        SdmxVersion extractedVersion = SdmxMediaTypeResolver.extractSdmxVersion(targetMediaType.toString());

        switch (extractedVersion) {
            case SDMX_3_0 -> {
                JsonWriterFactory writerFactory = new JsonWriterFactory(
                        objectMapper,
                        List.of(),
                        new DefaultReferenceAdapter(),
                        new StubDataStructureLocalRepresentationAdapter()
                );
                writerFactory.newInstance(outputStream).write(artefacts);
            }
            case SDMX_2_1 -> throw new UnsupportedConversionException(
                    "Availability conversion for SDMX 2.1 is not supported in MVP"
            );
            default -> throw new UnsupportedConversionException(
                    String.format("Unsupported SDMX version for availability conversion: %s", extractedVersion)
            );
        }
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
            SdmxFormat sourceFormat,
            MediaType targetMediaType
    ) {
        SdmxBeans sdmxBeans = parseAvailability(inputStream, sourceFormat);
        write(getArtefacts(sdmxBeans), outputStream, targetMediaType);
    }

    public SdmxBeans parseAvailability(InputStream inputStream, SdmxFormat registryFormat) {
        ReadableDataLocation location = sdmxSourceReadableDataLocationFactory.getReadableDataLocation(inputStream);
        StructureReaderFactory readerFactory = getParserFactory(registryFormat);
        return readerFactory.getSdmxBeans(location, iBeansBuilder);
    }

    private StructureReaderFactory getParserFactory(SdmxFormat returnFormat) {
        return switch (returnFormat) {
            // One factory covers 2.1 and 3.0: SdmxMLStructureReaderFactory dispatches on the
            // document's own namespace rather than the requested media type, returning
            // StaxStructureReaderEngineV3 for the v3_0 structure namespace.
            case XML_STRUCTURE_2_1, XML_STRUCTURE_3_0_0 -> sdmxMLStructureReaderFactory;
            case JSON_DATA_1_0_0 -> jsonV1StructureReaderFactory;
            case JSON_STRUCTURE_2_0_0 -> jsonV2StructureReaderFactory;
            default ->
                    throw new UnsupportedConversionException("Unsupported return format for parsing: " + returnFormat);
        };
    }


    private Artefacts getArtefacts(SdmxBeans sdmxBeans) {
        StructureMapperImpl structureMapper = new StructureMapperImpl();
        return structureMapper.map(sdmxBeans);
    }
}
