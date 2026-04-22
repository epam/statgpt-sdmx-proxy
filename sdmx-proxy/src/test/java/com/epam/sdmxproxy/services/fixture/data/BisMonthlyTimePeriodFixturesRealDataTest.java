package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests using real BIS response fixtures captured from
 * {@code https://stats.bis.org/api/v2/data/dataflow/BIS/WS_EER/1.0/M.N.B.DE} plus the BIS
 * dataflow structures. Runs each data format through its corresponding streaming fixture
 * and asserts that monthly TIME_PERIOD values are rewritten from BIS's {@code YYYY-MM} to the
 * canonical SDMX-JSON 2.0.0 {@code YYYY-Mmm} form (e.g. {@code 1994-01} -> {@code 1994-M01}).
 * <p>
 * Each format also runs through {@link StreamingDataConversionService} as a sanity check that
 * the fixture-wrapped stream still parses correctly and that normalized values propagate to
 * the SDMX-JSON 2.0.0 output.
 */
@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
class BisMonthlyTimePeriodFixturesRealDataTest {

    private static final String FIXTURES_ROOT = "bis/";
    private static final String JSON_DATA = "data_ws_eer_m_n_b_de_sdmx_json_1_0.json";
    private static final String CSV_DATA = "data_ws_eer_m_n_b_de_sdmx_csv_1_0.csv";
    private static final String XML_GENERIC_DATA = "data_ws_eer_m_n_b_de_sdmx_ml_2_1_generic.xml";
    private static final String XML_STRUCTURE_SPECIFIC_DATA = "data_ws_eer_m_n_b_de_sdmx_ml_2_1_structure_specific.xml";
    private static final String STRUCTURES = "structures_dataflow_bis_ws_eer_1_0_detail_full_references_descendants.json";
    private static final Pattern RAW_MONTHLY = Pattern.compile("\\b\\d{4}-\\d{2}\\b");
    private static final Pattern CANONICAL_MONTHLY = Pattern.compile("\\d{4}-M\\d{2}");

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StreamingStructureConversionService streamingStructureConversionService;
    @Autowired
    private StreamingDataConversionService streamingDataConversionService;
    @Autowired
    private TimePeriodMonthlyNormalizationJsonDataFixture jsonFixture;
    @Autowired
    private TimePeriodMonthlyNormalizationXmlGenericDataFixture xmlGenericFixture;
    @Autowired
    private TimePeriodMonthlyNormalizationXmlStructureSpecificDataFixture xmlStructureSpecificFixture;
    @Autowired
    private TimePeriodMonthlyNormalizationCsvDataFixture csvFixture;

    @Test
    @SneakyThrows
    void jsonFixture_rewritesMonthlyIdAndNameInBisJson100Response() {
        InputStream data = openFixture(JSON_DATA);
        try (InputStream result = jsonFixture.apply(data, null, new HashMap<>())) {
            JsonNode output = objectMapper.readTree(result);

            // SDMX-JSON 1.0.0 wraps dimensions in a singular `structure` object
            // (vs. SDMX-JSON 2.0.0 which uses an array `structures[]`).
            JsonNode observationDims = output.path("data").path("structure").path("dimensions").path("observation");
            assertTrue(observationDims.isArray() && observationDims.size() > 0,
                    "Expected data.dimensions.observation array in BIS JSON 1.0.0 response");

            JsonNode timePeriod = null;
            for (JsonNode dim : observationDims) {
                if ("TIME_PERIOD".equals(dim.path("id").asText())) {
                    timePeriod = dim;
                    break;
                }
            }
            assertTrue(timePeriod != null, "Observation dimensions must include TIME_PERIOD");

            JsonNode values = timePeriod.path("values");
            assertTrue(values.isArray() && values.size() > 0, "TIME_PERIOD values[] must be non-empty");

            int normalizedCount = 0;
            for (JsonNode value : values) {
                String id = value.path("id").asText();
                String name = value.path("name").asText();
                assertFalse(RAW_MONTHLY.matcher(id).matches(),
                        "TIME_PERIOD id must not be raw YYYY-MM: " + id);
                assertFalse(RAW_MONTHLY.matcher(name).matches(),
                        "TIME_PERIOD name must not be raw YYYY-MM: " + name);
                if (CANONICAL_MONTHLY.matcher(id).matches()) {
                    normalizedCount++;
                }
            }
            assertTrue(normalizedCount > 0, "Expected at least one canonical YYYY-Mmm value after normalization");

            // start/end ISO timestamps in each value entry must pass through unchanged
            JsonNode firstValue = values.get(0);
            assertTrue(firstValue.path("start").asText().contains("T"),
                    "start timestamp must remain an ISO datetime");
            assertTrue(firstValue.path("end").asText().contains("T"),
                    "end timestamp must remain an ISO datetime");
        }
    }

    @Test
    @SneakyThrows
    void xmlGenericFixture_rewritesObsDimensionValueOnBisGenericXml() {
        InputStream data = openFixture(XML_GENERIC_DATA);
        try (InputStream result = xmlGenericFixture.apply(data, null, new HashMap<>())) {
            String output = new String(result.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("value=\"1994-M01\""),
                    "Expected a normalized ObsDimension value (1994-M01) in generic XML output");
            assertFalse(Pattern.compile("<[^>]*ObsDimension[^>]*value=\"\\d{4}-\\d{2}\"").matcher(output).find(),
                    "No ObsDimension should retain a raw YYYY-MM value attribute");
        }
    }

    @Test
    @SneakyThrows
    void xmlStructureSpecificFixture_rewritesTimePeriodAttributeOnBisCompactXml() {
        InputStream data = openFixture(XML_STRUCTURE_SPECIFIC_DATA);
        try (InputStream result = xmlStructureSpecificFixture.apply(data, null, new HashMap<>())) {
            String output = new String(result.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("TIME_PERIOD=\"1994-M01\""),
                    "Expected a normalized TIME_PERIOD attribute (1994-M01) in structure-specific XML output");
            assertFalse(Pattern.compile("TIME_PERIOD=\"\\d{4}-\\d{2}\"").matcher(output).find(),
                    "No TIME_PERIOD attribute should retain a raw YYYY-MM value");
        }
    }

    @Test
    @SneakyThrows
    void csvFixture_rewritesTimePeriodColumnOnBisCsv() {
        InputStream data = openFixture(CSV_DATA);
        try (InputStream result = csvFixture.apply(data, null, new HashMap<>())) {
            String output = new String(result.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = output.split("\n");
            assertTrue(lines[0].contains("TIME_PERIOD"), "Header must be preserved verbatim: " + lines[0]);

            int timePeriodIdx = java.util.Arrays.asList(lines[0].split(",")).indexOf("TIME_PERIOD");
            assertTrue(timePeriodIdx >= 0, "TIME_PERIOD column must be present");

            int normalizedCount = 0;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                String[] fields = lines[i].split(",");
                if (timePeriodIdx >= fields.length) {
                    continue;
                }
                String tp = fields[timePeriodIdx];
                assertFalse(RAW_MONTHLY.matcher(tp).matches(),
                        "CSV row " + i + " TIME_PERIOD must not be raw YYYY-MM: " + tp);
                if (CANONICAL_MONTHLY.matcher(tp).matches()) {
                    normalizedCount++;
                }
            }
            assertTrue(normalizedCount > 0, "Expected at least one canonical YYYY-Mmm value in CSV TIME_PERIOD column");
        }
    }

    @Test
    @SneakyThrows
    void fullPipeline_jsonFixturePlusConversionProducesCanonicalJson200Output() {
        SdmxBeans structures = loadStructures();

        InputStream raw = openFixture(JSON_DATA);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (InputStream fixtured = jsonFixture.apply(raw, structures, new HashMap<>())) {
            streamingDataConversionService.convert(
                    fixtured,
                    sink,
                    structures,
                    ReturnFormat.JSON_1_0_0,
                    MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE)
            );
        }

        JsonNode output = objectMapper.readTree(sink.toByteArray());
        JsonNode timePeriodValues = output
                .path("data").path("structures").get(0)
                .path("dimensions").path("observation").get(0)
                .path("values");
        assertTrue(timePeriodValues.isArray() && timePeriodValues.size() > 0,
                "Converted output must expose TIME_PERIOD values");
        for (JsonNode value : timePeriodValues) {
            String literal = value.has("value") ? value.path("value").asText() : value.path("id").asText();
            assertFalse(RAW_MONTHLY.matcher(literal).matches(),
                    "Converted TIME_PERIOD value must not be raw YYYY-MM: " + literal);
        }
    }

    private SdmxBeans loadStructures() throws Exception {
        try (InputStream structures = openFixture(STRUCTURES)) {
            return streamingStructureConversionService.parseStructures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0);
        }
    }

    private InputStream openFixture(String name) {
        InputStream in = getClass().getResourceAsStream(FIXTURES_ROOT + name);
        if (in == null) {
            throw new IllegalStateException("Missing test fixture: " + FIXTURES_ROOT + name);
        }
        return in;
    }
}
