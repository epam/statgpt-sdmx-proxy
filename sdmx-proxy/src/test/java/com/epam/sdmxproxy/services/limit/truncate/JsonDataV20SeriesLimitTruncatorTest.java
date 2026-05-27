package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class JsonDataV20SeriesLimitTruncatorTest {

    private JsonDataV20SeriesLimitTruncator truncator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        truncator = new JsonDataV20SeriesLimitTruncator(mapper, new StreamingFixtureIO());
    }

    @Test
    void truncate_seriesAsObject_capsAtN() throws Exception {
        String body = bodyWithObjectSeries(12);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        assertThat(countObjectSeries(out, 0)).isEqualTo(10);
    }

    @Test
    void truncate_seriesAsArray_capsAtN() throws Exception {
        String body = bodyWithArraySeries(12);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        assertThat(countArraySeries(out, 0)).isEqualTo(10);
    }

    @Test
    void truncate_acrossMultipleDataSets_cumulative() throws Exception {
        String body = bodyWithTwoDataSetsObjectSeries(7, 7);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        // 10 cap is cumulative -- first data set keeps all 7, second keeps 3.
        assertThat(countObjectSeries(out, 0)).isEqualTo(7);
        assertThat(countObjectSeries(out, 1)).isEqualTo(3);
    }

    @Test
    void truncate_seriesEmpty_passesThrough() throws Exception {
        String body = "{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[{\"action\":\"Information\",\"series\":{}}]}}";

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        JsonNode root = mapper.readTree(out);
        JsonNode series = root.path("data").path("dataSets").get(0).path("series");
        assertThat(series.isObject()).isTrue();
        assertThat(series.size()).isZero();
        assertThat(root.path("meta").path("id").asText()).isEqualTo("sample");
    }

    @Test
    void truncate_seriesUnderN_unchanged() throws Exception {
        String body = bodyWithObjectSeries(5);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        assertThat(countObjectSeries(out, 0)).isEqualTo(5);
    }

    @Test
    void truncate_doesNotTouchAttributesSeriesArray() throws Exception {
        // Real-world SDMX-JSON 2.0 responses carry data.structures[*].attributes.series --
        // an array of series-level attribute *definitions*. Field name happens to be "series"
        // but it is not the data series and must pass through untouched.
        String body = bodyWithDataAndStructuresSeries(12, 21);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        // Data series capped at 10.
        assertThat(countObjectSeries(out, 0)).isEqualTo(10);

        // Attribute-definitions series array intact at 21.
        JsonNode root = mapper.readTree(out);
        JsonNode attrSeries = root.path("data").path("structures").get(0).path("attributes").path("series");
        assertThat(attrSeries.isArray()).isTrue();
        assertThat(attrSeries.size()).isEqualTo(21);
    }

    private int countObjectSeries(byte[] responseBody, int dataSetIndex) throws Exception {
        JsonNode series = mapper.readTree(responseBody).path("data").path("dataSets").get(dataSetIndex).path("series");
        int seen = 0;
        Iterator<String> names = series.fieldNames();
        while (names.hasNext()) {
            names.next();
            seen++;
        }
        return seen;
    }

    private int countArraySeries(byte[] responseBody, int dataSetIndex) throws Exception {
        return mapper.readTree(responseBody).path("data").path("dataSets").get(dataSetIndex).path("series").size();
    }

    private static String bodyWithObjectSeries(int series) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[{\"action\":\"Information\",\"series\":{");
        for (int i = 0; i < series; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(i).append(":0:0\":{\"observations\":{\"0\":[1.0]}}");
        }
        sb.append("}}]}}");
        return sb.toString();
    }

    private static String bodyWithArraySeries(int series) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[{\"action\":\"Information\",\"series\":[");
        for (int i = 0; i < series; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"dimensions\":[").append(i).append(",0,0],\"observations\":{\"0\":[1.0]}}");
        }
        sb.append("]}]}}");
        return sb.toString();
    }

    private static String bodyWithTwoDataSetsObjectSeries(int first, int second) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[");
        appendDataSetWithObjectSeries(sb, first, 0);
        sb.append(',');
        appendDataSetWithObjectSeries(sb, second, 100);
        sb.append("]}}");
        return sb.toString();
    }

    private static void appendDataSetWithObjectSeries(StringBuilder sb, int seriesCount, int idOffset) {
        sb.append("{\"action\":\"Information\",\"series\":{");
        for (int i = 0; i < seriesCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(idOffset + i).append(":0:0\":{\"observations\":{\"0\":[1.0]}}");
        }
        sb.append("}}");
    }

    private static String bodyWithDataAndStructuresSeries(int dataSeriesCount, int attrSeriesDefCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[{\"action\":\"Information\",\"series\":{");
        for (int i = 0; i < dataSeriesCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(i).append(":0:0\":{\"observations\":{\"0\":[1.0]}}");
        }
        sb.append("}}],\"structures\":[{\"attributes\":{\"series\":[");
        for (int i = 0; i < attrSeriesDefCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"ATTR_").append(i).append("\",\"relationship\":{}}");
        }
        sb.append("]}}]}}");
        return sb.toString();
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
