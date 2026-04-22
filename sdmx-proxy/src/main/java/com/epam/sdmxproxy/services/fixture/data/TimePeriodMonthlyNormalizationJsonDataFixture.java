package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes monthly {@code TIME_PERIOD} values in SDMX-JSON 1.0.0 and 2.0.0 data responses
 * to the canonical {@code YYYY-Mmm} form (e.g. {@code 2024-03} becomes {@code 2024-M03}).
 * <p>
 * Streaming end-to-end via Jackson's {@link JsonParser} / {@link JsonGenerator}, wired through
 * a pipe on a virtual thread (see {@link StreamingFixtureIO}). Peak memory per request is
 * bounded by the pipe buffer plus Jackson's internal block buffers, independent of response
 * size.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimePeriodMonthlyNormalizationJsonDataFixture implements DataFixture {

    private static final Pattern MONTHLY = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final String TIME_PERIOD = "TIME_PERIOD";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String VALUES = "values";
    private static final String VALUE = "value";

    private final ObjectMapper objectMapper;
    private final StreamingFixtureIO streamingFixtureIO;

    @Override
    public DataFixtureType getType() {
        return DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.JSON_1_0_0, ReturnFormat.JSON_DATA_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        JsonFactory factory = objectMapper.getFactory();
        return streamingFixtureIO.runOnVirtualThread(input, "data-fixture-monthly-time-period-json", (in, out) -> {
            try (JsonParser parser = factory.createParser(in);
                 JsonGenerator gen = factory.createGenerator(out)) {
                transform(parser, gen);
            }
        });
    }

    /**
     * Token-level streaming transform driven by an explicit depth counter.
     * <p>
     * State tracked:
     * <ul>
     *   <li>{@code depth} -- current container depth; incremented on START_{OBJECT,ARRAY},
     *       decremented on END_{OBJECT,ARRAY}.</li>
     *   <li>{@code fieldStack} -- enclosing-field name per container, pushed on container open
     *       and popped on close so {@code lastField} is restored when a nested container
     *       closes.</li>
     *   <li>{@code inTimePeriodDim} + {@code dimDepth} -- set when we see a string field
     *       {@code id == "TIME_PERIOD"} inside an object; cleared when END_OBJECT fires with
     *       depth still equal to {@code dimDepth} (strict equality, before decrement).</li>
     *   <li>{@code inValuesArray} + {@code valuesDepth} -- set when we enter a {@code values}
     *       array inside a TIME_PERIOD dim object; cleared on matching END_ARRAY.</li>
     * </ul>
     * When both flags are true and the current string field name is {@code value}, the value
     * is matched against {@link #MONTHLY} and rewritten to {@code YYYY-Mmm}. Non-matching
     * strings pass through verbatim, as do all other tokens.
     */
    private void transform(JsonParser parser, JsonGenerator gen) throws IOException {
        int depth = 0;
        String lastField = null;
        Deque<String> fieldStack = new ArrayDeque<>();

        boolean inTimePeriodDim = false;
        int dimDepth = -1;
        boolean inValuesArray = false;
        int valuesDepth = -1;

        JsonToken token = parser.nextToken();
        while (token != null) {
            switch (token) {
                case START_OBJECT -> {
                    fieldStack.push(lastField == null ? "" : lastField);
                    lastField = null;
                    depth++;
                    gen.writeStartObject();
                }
                case END_OBJECT -> {
                    if (inTimePeriodDim && depth == dimDepth) {
                        inTimePeriodDim = false;
                        dimDepth = -1;
                    }
                    depth--;
                    String restored = fieldStack.pop();
                    lastField = restored.isEmpty() ? null : restored;
                    gen.writeEndObject();
                }
                case START_ARRAY -> {
                    if (inTimePeriodDim && VALUES.equals(lastField) && !inValuesArray) {
                        inValuesArray = true;
                        valuesDepth = depth + 1;
                    }
                    fieldStack.push(lastField == null ? "" : lastField);
                    lastField = null;
                    depth++;
                    gen.writeStartArray();
                }
                case END_ARRAY -> {
                    if (inValuesArray && depth == valuesDepth) {
                        inValuesArray = false;
                        valuesDepth = -1;
                    }
                    depth--;
                    String restored = fieldStack.pop();
                    lastField = restored.isEmpty() ? null : restored;
                    gen.writeEndArray();
                }
                case FIELD_NAME -> {
                    lastField = parser.currentName();
                    gen.writeFieldName(lastField);
                }
                case VALUE_STRING -> {
                    String text = parser.getText();
                    if (!inTimePeriodDim && ID.equals(lastField) && TIME_PERIOD.equals(text)) {
                        inTimePeriodDim = true;
                        dimDepth = depth;
                    }
                    if (inValuesArray && isPeriodLiteralField(lastField)) {
                        gen.writeString(normalize(text));
                    } else {
                        gen.writeString(text);
                    }
                }
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
     * The period literal inside a TIME_PERIOD dimension {@code values[]} entry is carried by
     * one of several SDMX-JSON field aliases depending on the schema version and registry:
     * {@code value} (SDMX-JSON 2.0.0), {@code id} and {@code name} (SDMX-JSON 1.0.0 as emitted
     * by BIS). All three are rewritten; other fields like {@code start}/{@code end} are full
     * ISO timestamps that never match the monthly pattern and pass through.
     */
    private boolean isPeriodLiteralField(String fieldName) {
        return VALUE.equals(fieldName) || ID.equals(fieldName) || NAME.equals(fieldName);
    }

    private String normalize(String value) {
        Matcher matcher = MONTHLY.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(1) + "-M" + matcher.group(2);
    }
}
