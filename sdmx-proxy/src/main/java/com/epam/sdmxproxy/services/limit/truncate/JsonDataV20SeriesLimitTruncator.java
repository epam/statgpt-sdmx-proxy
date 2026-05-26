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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
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

    // Marker pushed onto the container stack for an anonymous array element (an object that is
     // not introduced by a field name but by an enclosing array). Using a sentinel rather than
     // null because ArrayDeque rejects null elements.
    private static final String ARRAY_ELEMENT = "\0array-element";

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
        // containerStack[top] = field name that introduced the current enclosing object/array,
        // or null when the current container is an anonymous array element. Used to discriminate
        // the data series (data.dataSets[*].series) from incidental occurrences of a `series`
        // field elsewhere -- notably data.structures[*].attributes.series, the DSD-derived
        // attribute-definition list, which must NOT be truncated.
        Deque<String> containerStack = new ArrayDeque<>();
        String lastField = null;
        JsonToken token = parser.nextToken();
        while (token != null) {
            switch (token) {
                case FIELD_NAME -> {
                    lastField = parser.currentName();
                    gen.writeFieldName(lastField);
                }
                case START_ARRAY -> {
                    boolean truncateHere = "series".equals(lastField) && isInsideDataSetsElement(containerStack);
                    containerStack.push(lastField == null ? ARRAY_ELEMENT : lastField);
                    gen.writeStartArray();
                    if (truncateHere) {
                        copySeriesArray(parser, gen, n, seriesCount);
                        gen.writeEndArray();
                        containerStack.pop();
                    }
                    lastField = null;
                }
                case END_ARRAY -> {
                    gen.writeEndArray();
                    containerStack.pop();
                    lastField = null;
                }
                case START_OBJECT -> {
                    boolean truncateHere = "series".equals(lastField) && isInsideDataSetsElement(containerStack);
                    containerStack.push(lastField == null ? ARRAY_ELEMENT : lastField);
                    gen.writeStartObject();
                    if (truncateHere) {
                        copySeriesObject(parser, gen, n, seriesCount);
                        gen.writeEndObject();
                        containerStack.pop();
                    }
                    lastField = null;
                }
                case END_OBJECT -> {
                    gen.writeEndObject();
                    containerStack.pop();
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
     * True when the enclosing container -- about to receive a {@code "series"} field -- is an
     * anonymous array element of a container named {@code "dataSets"}. That is the only place
     * the SDMX-JSON 2.0 spec puts the data series; any other {@code "series"} field (e.g. inside
     * {@code data.structures[*].attributes}) is an attribute-definition list and must pass
     * through untouched.
     */
    private static boolean isInsideDataSetsElement(Deque<String> containerStack) {
        if (containerStack.size() < 2) {
            return false;
        }
        Iterator<String> it = containerStack.iterator();
        if (!ARRAY_ELEMENT.equals(it.next())) {
            return false;
        }
        return "dataSets".equals(it.next());
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

    /**
     * Assumes {@code parser} is positioned at START_OBJECT of a {@code series} map
     * (the standard SDMX-JSON 2.0 form, keyed by series id like "0:0:0"). Consumes
     * through (but does not re-emit) the matching END_OBJECT. Caller writes the
     * closing brace on {@code gen}.
     */
    @SneakyThrows
    private static void copySeriesObject(JsonParser parser, JsonGenerator gen, int n, int[] seriesCount) {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            if (seriesCount[0] < n) {
                gen.writeFieldName(key);
                gen.copyCurrentStructure(parser);
                seriesCount[0]++;
            } else {
                parser.skipChildren();
            }
        }
    }
}
