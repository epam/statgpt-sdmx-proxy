package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityProbeException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming Jackson parser for SDMX-JSON 2.0.0 availability responses.
 * <p>
 * Supports both shapes seen in the wild:
 * <ul>
 *   <li>{@code data.dataConstraints[*].cubeRegions[*].components[*].{id, values[*].value}}
 *       (IMF and SDMX-JSON 2.0.0 strict form)</li>
 *   <li>{@code data.dataConstraints[*].cubeRegions[*].keyValues[*].{id, values[*].value}}
 *       (BIS FusionRegistry emits {@code keyValues} instead of {@code components})</li>
 * </ul>
 * Additionally extracts the optional {@code series_count} annotation
 * ({@code annotations[{id:"series_count", title:"<number>"}]}) on each data constraint;
 * the largest value seen across all data constraints is recorded on the projection. This
 * annotation -- when present -- gives the exact number of series in the filtered cube,
 * which is far more useful to the shrink loop than a combinatorial upper bound. When absent
 * the projection falls back to computing {@code prod |A_d|} from the per-dim values.
 */
@Slf4j
@Component
public class JsonAvailabilityResponseParser implements AvailabilityResponseParser {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final String SERIES_COUNT_ANNOTATION_ID = "series_count";

    @Override
    public boolean supports(SdmxFormat format) {
        return format == SdmxFormat.JSON_STRUCTURE_2_0_0;
    }

    @Override
    public AvailabilityProjection parse(InputStream availabilityResponseStream, SdmxFormat format) {
        if (!supports(format)) {
            throw new AvailabilityProbeException(
                    "Unsupported availability return format for JSON parser: " + format);
        }
        Map<String, LinkedHashMap<String, Boolean>> accumulator = new LinkedHashMap<>();
        long[] maxSeriesCount = {-1L};
        try (JsonParser parser = JSON_FACTORY.createParser(availabilityResponseStream)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Availability response is not a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                if ("data".equals(parser.currentName())) {
                    parser.nextToken();
                    parseDataObject(parser, accumulator, maxSeriesCount);
                } else {
                    parser.nextToken();
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new AvailabilityProbeException("Failed to parse availability response", e);
        }

        Map<String, List<String>> result = new LinkedHashMap<>(accumulator.size());
        for (Map.Entry<String, LinkedHashMap<String, Boolean>> e : accumulator.entrySet()) {
            result.put(e.getKey(), List.copyOf(e.getValue().keySet()));
        }
        Long seriesCount = maxSeriesCount[0] >= 0L ? maxSeriesCount[0] : null;
        log.debug("Parsed availability: dims={}, seriesCountAnnotation={}",
                result.keySet(), seriesCount);
        return new AvailabilityProjection(result, seriesCount);
    }

    @SneakyThrows
    private static void parseDataObject(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator,
            long[] maxSeriesCount
    ) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            if ("dataConstraints".equals(parser.currentName())) {
                parser.nextToken();
                parseDataConstraintsArray(parser, accumulator, maxSeriesCount);
            } else {
                parser.nextToken();
                parser.skipChildren();
            }
        }
    }

    @SneakyThrows
    private static void parseDataConstraintsArray(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator,
            long[] maxSeriesCount
    ) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            parseDataConstraint(parser, accumulator, maxSeriesCount);
        }
    }

    @SneakyThrows
    private static void parseDataConstraint(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator,
            long[] maxSeriesCount
    ) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "cubeRegions" -> parseCubeRegionsArray(parser, accumulator);
                case "annotations" -> updateMaxSeriesCount(parser, maxSeriesCount);
                default -> parser.skipChildren();
            }
        }
    }

    @SneakyThrows
    private static void updateMaxSeriesCount(JsonParser parser, long[] maxSeriesCount) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String id = null;
            String title = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                switch (name) {
                    case "id" -> id = parser.getValueAsString();
                    case "title" -> title = parser.getValueAsString();
                    default -> parser.skipChildren();
                }
            }
            if (SERIES_COUNT_ANNOTATION_ID.equals(id) && title != null) {
                try {
                    long value = Long.parseLong(title.trim());
                    if (value > maxSeriesCount[0]) {
                        maxSeriesCount[0] = value;
                    }
                } catch (NumberFormatException ignored) {
                    // Registry emitted a non-numeric series_count; ignore and fall back to
                    // combinatorial upper bound. A WARN is logged so operators can file
                    // a registry bug report if it recurs.
                    log.warn("Unparseable series_count annotation title: '{}'", title);
                }
            }
        }
    }

    @SneakyThrows
    private static void parseCubeRegionsArray(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator
    ) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            parseCubeRegion(parser, accumulator);
        }
    }

    @SneakyThrows
    private static void parseCubeRegion(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator
    ) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if ("components".equals(field) || "keyValues".equals(field)) {
                parseComponentsArray(parser, accumulator);
            } else {
                parser.skipChildren();
            }
        }
    }

    @SneakyThrows
    private static void parseComponentsArray(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator
    ) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            parseComponent(parser, accumulator);
        }
    }

    @SneakyThrows
    private static void parseComponent(
            JsonParser parser,
            Map<String, LinkedHashMap<String, Boolean>> accumulator
    ) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        String id = null;
        List<String> pendingValues = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "id" -> id = parser.getValueAsString();
                case "values" -> pendingValues = parseValuesArray(parser);
                default -> parser.skipChildren();
            }
        }
        if (id != null && pendingValues != null) {
            LinkedHashMap<String, Boolean> bucket =
                    accumulator.computeIfAbsent(id, k -> new LinkedHashMap<>());
            for (String v : pendingValues) {
                bucket.putIfAbsent(v, Boolean.TRUE);
            }
        }
    }

    @SneakyThrows
    private static List<String> parseValuesArray(JsonParser parser) {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return List.of();
        }
        List<String> out = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String fn = parser.currentName();
                    parser.nextToken();
                    if ("value".equals(fn)) {
                        String v = parser.getValueAsString();
                        if (v != null) {
                            out.add(v);
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
            } else if (parser.currentToken() == JsonToken.VALUE_STRING) {
                out.add(parser.getValueAsString());
            } else {
                parser.skipChildren();
            }
        }
        return out;
    }
}
