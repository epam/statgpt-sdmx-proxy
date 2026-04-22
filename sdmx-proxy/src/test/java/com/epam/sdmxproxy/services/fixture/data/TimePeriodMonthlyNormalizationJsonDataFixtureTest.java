package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimePeriodMonthlyNormalizationJsonDataFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TimePeriodMonthlyNormalizationJsonDataFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TimePeriodMonthlyNormalizationJsonDataFixture(MAPPER, new StreamingFixtureIO());
    }

    @Test
    void getType_returnsMonthlyNormalization() {
        assertEquals(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION, fixture.getType());
    }

    @Test
    void supportedFormats_containsJson100AndJson200() {
        assertEquals(2, fixture.supportedFormats().size());
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.JSON_1_0_0));
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.JSON_DATA_2_0_0));
    }

    @Test
    void apply_rewritesMonthlyTimePeriodValues() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"TIME_PERIOD","keyPosition":3,"values":[
                    {"value":"2024-03"},
                    {"value":"2024-04"},
                    {"value":"2024-12"}
                  ]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode values = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");

        assertEquals("2024-M03", values.get(0).path("value").asText());
        assertEquals("2024-M04", values.get(1).path("value").asText());
        assertEquals("2024-M12", values.get(2).path("value").asText());
    }

    @Test
    void apply_rewritesIdAndNameAliasesForBisShape() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"TIME_PERIOD","name":"Time period or range","values":[
                    {"start":"2024-03-01T00:00:00","end":"2024-03-31T23:59:59","id":"2024-03","name":"2024-03"},
                    {"start":"2024-04-01T00:00:00","end":"2024-04-30T23:59:59","id":"2024-04","name":"2024-04"}
                  ]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode observation = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0);

        assertEquals("TIME_PERIOD", observation.path("id").asText(), "dimension id must stay 'TIME_PERIOD'");
        assertEquals("Time period or range", observation.path("name").asText(), "dimension name must be unchanged");

        JsonNode values = observation.path("values");
        assertEquals("2024-M03", values.get(0).path("id").asText());
        assertEquals("2024-M03", values.get(0).path("name").asText());
        assertEquals("2024-04-01T00:00:00", values.get(1).path("start").asText(), "start ISO timestamp must pass through unchanged");
        assertEquals("2024-04-30T23:59:59", values.get(1).path("end").asText(), "end ISO timestamp must pass through unchanged");
        assertEquals("2024-M04", values.get(1).path("id").asText());
        assertEquals("2024-M04", values.get(1).path("name").asText());
    }

    @Test
    void apply_leavesCanonicalValuesUnchanged() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"TIME_PERIOD","values":[{"value":"2024-M03"},{"value":"2024-M04"}]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode values = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");

        assertEquals("2024-M03", values.get(0).path("value").asText());
        assertEquals("2024-M04", values.get(1).path("value").asText());
    }

    @Test
    void apply_ignoresAnnualValues() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"TIME_PERIOD","values":[{"value":"2024"},{"value":"2025"}]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode values = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");

        assertEquals("2024", values.get(0).path("value").asText());
        assertEquals("2025", values.get(1).path("value").asText());
    }

    @Test
    void apply_ignoresDailyAndQuarterlyValues() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"TIME_PERIOD","values":[
                    {"value":"2024-03-15"},
                    {"value":"2024-Q1"},
                    {"value":"2024-W10"}
                  ]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode values = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");

        assertEquals("2024-03-15", values.get(0).path("value").asText());
        assertEquals("2024-Q1", values.get(1).path("value").asText());
        assertEquals("2024-W10", values.get(2).path("value").asText());
    }

    @Test
    void apply_ignoresNonTimePeriodDimensionValues() throws IOException {
        String input = """
                {"data":{"structures":[{"dimensions":{"observation":[
                  {"id":"FREQ","values":[{"value":"2024-03"}]},
                  {"id":"TIME_PERIOD","values":[{"value":"2024-03"}]}
                ]}}]}}""";

        JsonNode output = applyAndRead(input);
        JsonNode observations = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation");

        assertEquals("2024-03", observations.get(0).path("values").get(0).path("value").asText(),
                "non-TIME_PERIOD dimension values must pass through verbatim");
        assertEquals("2024-M03", observations.get(1).path("values").get(0).path("value").asText());
    }

    @Test
    void apply_preservesOtherJsonStructureAndScalars() throws IOException {
        String input = """
                {
                  "meta": {"schema":"https://stats.bis.org","id":"abc","prepared":null,"count":42},
                  "data": {
                    "structures": [{
                      "dimensions": {
                        "series": [
                          {"id":"FREQ","keyPosition":0,"values":[{"value":"M","name":"Monthly"}]}
                        ],
                        "observation": [
                          {"id":"TIME_PERIOD","keyPosition":3,"values":[{"value":"2024-03"}]}
                        ]
                      }
                    }]
                  }
                }""";

        JsonNode output = applyAndRead(input);
        assertEquals("https://stats.bis.org", output.path("meta").path("schema").asText());
        assertEquals("abc", output.path("meta").path("id").asText());
        assertTrue(output.path("meta").path("prepared").isNull());
        assertEquals(42, output.path("meta").path("count").asInt());
        assertEquals("M",
                output.path("data").path("structures").get(0)
                        .path("dimensions").path("series").get(0)
                        .path("values").get(0).path("value").asText(),
                "FREQ dimension value 'M' must pass through unchanged");
        assertEquals("2024-M03",
                output.path("data").path("structures").get(0)
                        .path("dimensions").path("observation").get(0)
                        .path("values").get(0).path("value").asText());
    }

    @Test
    void apply_handlesBisLikeFullResponseShape() throws IOException {
        String input = """
                {
                  "meta": {"schema":"https://stats.bis.org/bis-json-schema.json","id":"WS_CBPOL"},
                  "data": {
                    "structures": [{
                      "dimensions": {
                        "dataSet": [],
                        "series": [
                          {"id":"FREQ","keyPosition":0,"values":[{"value":"M"}]},
                          {"id":"COUNTRY","keyPosition":1,"values":[{"value":"US"}]}
                        ],
                        "observation": [
                          {"id":"TIME_PERIOD","keyPosition":2,"values":[
                            {"value":"2023-11"},
                            {"value":"2023-12"},
                            {"value":"2024-01"},
                            {"value":"2024-02"}
                          ]}
                        ]
                      },
                      "attributes": {
                        "observation": [
                          {"id":"OBS_STATUS","values":[{"value":"A"}]}
                        ]
                      }
                    }],
                    "dataSets": [{
                      "action":"Information",
                      "series":{
                        "0:0":{"observations":{"0":[5.25,0],"1":[5.25,0],"2":[5.5,0],"3":[5.5,0]}}
                      }
                    }]
                  }
                }""";

        JsonNode output = applyAndRead(input);
        JsonNode timeValues = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");
        assertEquals("2023-M11", timeValues.get(0).path("value").asText());
        assertEquals("2023-M12", timeValues.get(1).path("value").asText());
        assertEquals("2024-M01", timeValues.get(2).path("value").asText());
        assertEquals("2024-M02", timeValues.get(3).path("value").asText());

        assertEquals("A",
                output.path("data").path("structures").get(0)
                        .path("attributes").path("observation").get(0)
                        .path("values").get(0).path("value").asText(),
                "OBS_STATUS attribute value must pass through unchanged");

        JsonNode obs = output.path("data").path("dataSets").get(0)
                .path("series").path("0:0").path("observations");
        assertEquals(5.25, obs.path("0").get(0).asDouble());
        assertEquals(5.5, obs.path("3").get(0).asDouble());
    }

    @Test
    void apply_propagatesProducerFailureAsIoException() {
        InputStream malformed = new ByteArrayInputStream("{not valid json".getBytes(StandardCharsets.UTF_8));
        InputStream result = fixture.apply(malformed, null, new HashMap<>());

        assertThrows(IOException.class, () -> {
            byte[] buf = new byte[256];
            int total = 0;
            int n;
            while ((n = result.read(buf)) >= 0) {
                total += n;
                if (total > 1_000_000) {
                    break;
                }
            }
        });
    }

    private JsonNode applyAndRead(String input) throws IOException {
        InputStream source = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        try (InputStream result = fixture.apply(source, null, new HashMap<>())) {
            return MAPPER.readTree(result);
        }
    }
}
