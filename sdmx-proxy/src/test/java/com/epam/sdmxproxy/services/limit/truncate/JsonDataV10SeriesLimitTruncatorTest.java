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

class JsonDataV10SeriesLimitTruncatorTest {

    private JsonDataV10SeriesLimitTruncator truncator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        truncator = new JsonDataV10SeriesLimitTruncator(mapper, new StreamingFixtureIO());
    }

    @Test
    void truncate_emitsFirstNSeries() throws Exception {
        String body = dataBody(10);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 3, null)) {
            out = in.readAllBytes();
        }

        assertThat(countSeries(out)).isEqualTo(3);
    }

    @Test
    void truncate_undershootEmitsAllSeries() throws Exception {
        String body = dataBody(2);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 10, null)) {
            out = in.readAllBytes();
        }

        assertThat(countSeries(out)).isEqualTo(2);
    }

    @Test
    void truncate_preservesEnvelopeAndMeta() throws Exception {
        String body = dataBody(5);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(body), 2, null)) {
            out = in.readAllBytes();
        }
        JsonNode root = mapper.readTree(out);
        assertThat(root.has("meta")).isTrue();
        assertThat(root.path("meta").path("id").asText()).isEqualTo("sample");
        assertThat(root.path("data").path("dataSets").get(0).path("action").asText()).isEqualTo("Information");
    }

    @Test
    void emptyStream_isWellFormedJson() throws Exception {
        try (InputStream in = truncator.emptyStream(null)) {
            byte[] bytes = in.readAllBytes();
            JsonNode root = mapper.readTree(bytes);
            assertThat(root.has("data")).isTrue();
            assertThat(root.path("data").path("dataSets")).isNotNull();
        }
    }

    private int countSeries(byte[] responseBody) throws Exception {
        JsonNode series = mapper.readTree(responseBody).path("data").path("dataSets").get(0).path("series");
        int seen = 0;
        Iterator<String> names = series.fieldNames();
        while (names.hasNext()) {
            names.next();
            seen++;
        }
        return seen;
    }

    private static String dataBody(int series) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"meta\":{\"id\":\"sample\"},\"data\":{\"dataSets\":[{\"action\":\"Information\",\"series\":{");
        for (int i = 0; i < series; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(i).append(":0:0:0\":{\"observations\":{\"0\":[\"1.0\"]}}");
        }
        sb.append("}}]}}");
        return sb.toString();
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
