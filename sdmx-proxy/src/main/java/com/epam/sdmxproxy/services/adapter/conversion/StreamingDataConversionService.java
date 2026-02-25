package com.epam.sdmxproxy.services.adapter.conversion;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxJsonDataReaderFactory;
import com.epam.sdmxproxy.services.sdmxsource.JsonDataWriterFactoryProducer;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.constants.DATA_TYPE;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.engine.ISeriesObsDataWriterEngine;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.data.DataFormat;
import io.sdmx.core.data.util.DataTransformOptions;
import io.sdmx.core.data.util.DataTransformationUtil;
import io.sdmx.core.sdmx.api.factory.data.DataReaderFactory;
import io.sdmx.core.sdmx.manager.structure.InMemoryRetrievalManager;
import io.sdmx.format.json.model.SdmxJsonDataFormat;
import io.sdmx.format.ml.factory.data.SdmxMLDataReaderFactory;
import io.sdmx.format.ml.model.SDMXMLDataFormat;
import io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.NoSuchElementException;

/**
 * Service that orchestrates the streaming conversion of SDMX data between different formats.
 * Supports conversion from XML/JSON to JSON format based on source format and target media type.
 * The conversion is fully streaming - data flows directly from input to output without being held in memory.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StreamingDataConversionService {

    private final SdmxSourceReadableDataLocationFactory readableDataLocationFactory;
    private final CustomSdmxJsonDataReaderFactory sdmxJsonDataReaderFactory;
    private final SdmxMLDataReaderFactory sdmxMLDataReaderFactory;
    private final JsonDataWriterFactoryProducer jsonDataWriterFactoryProducer;

    /**
     * Converts SDMX data from one format to another.
     *
     * @param inputStream     input stream in sourceFormat
     * @param outputStream    output stream in targetFormat
     * @param sourceFormat    source format (registry format)
     * @param targetMediaType target media type (requested format)
     * @param sdmxBeans       structure beans needed for conversion
     * @throws IOException if conversion fails
     */
    public void convert(
            InputStream inputStream,
            OutputStream outputStream,
            SdmxBeans sdmxBeans,
            ReturnFormat sourceFormat,
            MediaType targetMediaType
    ) throws IOException {
        // Convert based on target media type
        if (isJsonMediaType(targetMediaType)) {
            processAsJson(inputStream, outputStream, sdmxBeans, targetMediaType, sourceFormat);
            return;
        }

        if (isXmlMediaType(targetMediaType)) {
            throw new UnsupportedOperationException(
                    String.format("Data conversion to XML format (%s) is not supported in MVP", targetMediaType)
            );
        }

        throw new UnsupportedOperationException(
                String.format("Data conversion to %s is not supported", targetMediaType)
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
            SdmxBeans sdmxBeans,
            MediaType targetMediaType,
            ReturnFormat sourceFormat
    ) {
        DataReaderEngine reader = getDataReader(sdmxBeans, inputStream, sourceFormat);

        // Determine SDMX version from target media type
        SdmxVersion sdmxVersion = SdmxMediaType.extractSdmxVersion(targetMediaType.toString());
        ISeriesObsDataWriterEngine writer = getDataWriterEngine(sdmxBeans, outputStream, sdmxVersion);

        DataTransformOptions options = DataTransformOptions.getInstance();
        options.setCopyHeader(true);
        options.setCloseWriter(true);

        DataTransformationUtil.copyData(reader, writer, options);

    }

    private DataReaderEngine getDataReader(SdmxBeans sdmxBeans, InputStream inputStream, ReturnFormat sourceFormat) {
        ReadableDataLocation dataLocation = readableDataLocationFactory.getReadableDataLocation(inputStream);

        DataReaderFactory dataReaderFactory = isJsonReturnType(sourceFormat)
                ? sdmxJsonDataReaderFactory
                : sdmxMLDataReaderFactory;

        return dataReaderFactory.getDataReaderEngine(
                getSdmxDataFormat(sourceFormat),
                dataLocation,
                new InMemoryRetrievalManager(sdmxBeans),
                null
        );
    }

    private boolean isJsonReturnType(ReturnFormat sourceFormat) {
        return sourceFormat == ReturnFormat.JSON_1_0_0 || sourceFormat == ReturnFormat.JSON_DATA_2_0_0;
    }

    private DataFormat getSdmxDataFormat(ReturnFormat sourceFormat) {
        switch (sourceFormat) {
            case JSON_1_0_0 -> {
                return new SdmxJsonDataFormat(DATA_TYPE.SDMXJSON_1_0_0, null);
            }
            case JSON_DATA_2_0_0 -> {
                return new SdmxJsonDataFormat(DATA_TYPE.SDMXJSON_2_0_0, null);
            }
            case XML_GENERICDATA_2_1 -> {
                return SDMXMLDataFormat.GENERIC_2_1;
            }
            case XML_STRUCTURE_SPECIFIC_2_1 -> {
                return SDMXMLDataFormat.COMPACT_2_1;
            }
            default -> throw new IllegalArgumentException("Cannot use this format for data");
        }
    }


    private ISeriesObsDataWriterEngine getDataWriterEngine(SdmxBeans sdmxBeans, OutputStream outputStream, SdmxVersion sdmxVersion) {
        DataFormat dataFormat;
        switch (sdmxVersion) {
            case SDMX_2_1 -> {
                log.debug("Using JsonV10DataWriterEngine for SDMX 2.1 data");
                dataFormat = new SdmxJsonDataFormat(DATA_TYPE.SDMXJSON_1_0_0, null);
            }
            case SDMX_3_0 -> {
                log.debug("Using JsonV20DataWriterEngine for SDMX 3.0 data");
                dataFormat = new SdmxJsonDataFormat(DATA_TYPE.SDMXJSON_2_0_0, null);
            }
            default ->
                    throw new UnsupportedOperationException("Unsupported SDMX version for data conversion: " + sdmxVersion);
        }

        return jsonDataWriterFactoryProducer.getDataWriterFactory(new InMemoryRetrievalManager(sdmxBeans)).getDataWriterEngine(
                dataFormat,
                outputStream,
                null
        );
    }

    private static class DsdAndDataflow {
        DataStructureBean dsd;
        DataflowBean dataflow;

        DsdAndDataflow(SdmxBeans sdmxBeans) {
            this.dsd = sdmxBeans.getDataStructures().stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No dataStructure present"));
            this.dataflow = sdmxBeans.getDataflows().stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No dataflow present"));
        }
    }
}
