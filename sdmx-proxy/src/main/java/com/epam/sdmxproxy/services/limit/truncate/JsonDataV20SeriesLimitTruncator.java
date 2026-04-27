package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Streaming truncator for SDMX-JSON 2.0.0 data responses. In this format the series are a
 * JSON array under {@code data.dataSets[*].series}; each element is an object with
 * {@code dimensions}/{@code attributes}/{@code observations} fields. Implementation copies
 * the first {@code n} array elements cumulatively across all data sets; envelope fields
 * flow through unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonDataV20SeriesLimitTruncator implements SeriesLimitTruncator {

    private static final byte[] EMPTY_PAYLOAD =
            "{\"meta\":{\"id\":\"empty\"},\"data\":{\"dataSets\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;
    private final StreamingFixtureIO streamingFixtureIO;

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.JSON_DATA_2_0_0);
    }

    @Override
    public InputStream truncate(InputStream rawData, int n, SdmxBeans sdmxBeans) {
        JsonFactory factory = objectMapper.getFactory();
        return streamingFixtureIO.runOnVirtualThread(
                rawData,
                "limit-truncate-json-2-0",
                (in, out) -> {
                    try (JsonParser parser = factory.createParser(in);
                         JsonGenerator gen = factory.createGenerator(out)) {
                        transform(parser, gen, n);
                    }
                }
        );
    }

    @Override
    public InputStream emptyStream(SdmxBeans sdmxBeans) {
        return new ByteArrayInputStream(EMPTY_PAYLOAD);
    }

    @SneakyThrows
    private static void transform(JsonParser parser, JsonGenerator gen, int n) {
        int[] seriesCount = {0};
        String lastField = null;
        JsonToken token = parser.nextToken();
        while (token != null) {
            switch (token) {
                case FIELD_NAME -> {
                    lastField = parser.currentName();
                    gen.writeFieldName(lastField);
                }
                case START_ARRAY -> {
                    gen.writeStartArray();
                    if ("series".equals(lastField)) {
                        copySeriesArray(parser, gen, n, seriesCount);
                        gen.writeEndArray();
                    }
                    lastField = null;
                }
                case END_ARRAY -> {
                    gen.writeEndArray();
                    lastField = null;
                }
                case START_OBJECT -> {
                    gen.writeStartObject();
                    lastField = null;
                }
                case END_OBJECT -> {
                    gen.writeEndObject();
                    lastField = null;
                }
                case VALUE_STRING -> gen.writeString(parser.getText());
                case VALUE_NUMBER_INT -> gen.writeNumber(parser.getLongValue());
                case VALUE_NUMBER_FLOAT -> gen.writeNumber(parser.getDecimalValue());
                case VALUE_TRUE -> gen.writeBoolean(true);
                case VALUE_FALSE -> gen.writeBoolean(false);
                case VALUE_NULL -> gen.writeNull();
                case VALUE_EMBEDDED_OBJECT -> gen.writeObject(parser.getEmbeddedObject());
                default -> {
                    // NOT_AVAILABLE should not appear in a blocking parser
                }
            }
            token = parser.nextToken();
        }
    }

    /**
     * Assumes {@code parser} is positioned at START_ARRAY of a {@code series} array.
     * Consumes through (but does not re-emit) the matching END_ARRAY. Caller writes the
     * closing bracket on {@code gen}.
     */
    @SneakyThrows
    private static void copySeriesArray(JsonParser parser, JsonGenerator gen, int n, int[] seriesCount) {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (seriesCount[0] < n) {
                gen.copyCurrentStructure(parser);
                seriesCount[0]++;
            } else {
                parser.skipChildren();
            }
        }
    }
}
