package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class StreamingDataConversionServiceTest {
    @Autowired
    private StreamingStructureConversionService streamingStructureConversionService;

    @Autowired
    private StreamingDataConversionService streamingDataConversionService;

    @Autowired
    private ObjectMapper objectMapper;


    @Test
    @SneakyThrows
    void shouldConvertDataWithoutLosingObservations() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
        InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.XML_STRUCTURE_2_1);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode observations = jsonNode.path("data").path("dataSets").get(0).path("series").path("0:0:0").get("observations");
        for (int i = 0; i < observations.size(); i++) {
            assertFalse(observations.get(String.valueOf(i)).get(0).isNull());
        }

    }


    @Test
    @SneakyThrows
    void shouldConvertData_ime_res_weo_currentStructureIndex_Null() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode firstDataSet = jsonNode.path("data").path("dataSets").get(0);

        // Original regression: observations were dropped when currentStructureIndex was null.
        JsonNode observations = firstDataSet.path("series").path("0:0:0").get("observations");
        for (int i = 0; i < observations.size(); i++) {
            assertFalse(observations.get(String.valueOf(i)).get(0).isNull());
        }
    }

    @Test
    @SneakyThrows
    void shouldConvertData_ime_res_weo_no_query_params_in_proxy() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_no_query_params_to_proxy.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

    }


    @Test
    @SneakyThrows
    void shouldConvertData_ime_res_weo_without_adding_new_series_attributes_and_reseting_obs_indexes() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_2026-2028_period.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        //THEN
        JsonNode jsonNode = objectMapper.readTree(outputStream.toByteArray());
        JsonNode dataSet = jsonNode.path("data").path("dataSets").get(0);
        JsonNode series = dataSet.path("series");

        assertEquals(2, series.size(), "Expected exactly 2 series (0:0:0 and 0:1:0)");

        for (String seriesKey : List.of("0:0:0", "0:1:0")) {
            JsonNode observations = series.path(seriesKey).path("observations");
            assertEquals(51, observations.size(), "Series " + seriesKey + " should have 51 observations (keys 0..50)");
            for (int i = 0; i <= 50; i++) {
                String key = String.valueOf(i);
                assertTrue(observations.has(key), "Series " + seriesKey + " observations should contain key \"" + key + "\"");
                JsonNode obs = observations.get(key);
                assertTrue(obs.isArray() && obs.size() >= 1, "Series " + seriesKey + " observation " + key + " should be non-empty array");
                assertFalse(obs.get(0).isNull(), "Series " + seriesKey + " observation " + key + " value should not be null");
            }
        }
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromXml21ToXml30() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
        InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");
        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.XML_STRUCTURE_2_1);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, MediaType.valueOf(SdmxMediaTypes.DATA_XML_3_0_0));

        //THEN
        String xml = outputStream.toString(StandardCharsets.UTF_8);
        assertFalse(xml.isEmpty(), "Output should not be empty");
        assertTrue(xml.contains("DataSet"), "Output should contain DataSet element");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromXml21ToCsv20() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
        InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");
        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.XML_STRUCTURE_2_1);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0));

        //THEN
        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "CSV should have header + data rows");
        assertTrue(lines[0].contains("STRUCTURE"), "CSV header should contain STRUCTURE column");
        // Regression guard for the OBS_VALUE-drop bug — see issue #49 / design 016.
        // Pre-fix this counted 0 populated rows.
        assertTrue(countPopulatedObsValueRows(csv) > 0,
                "OBS_VALUE column should have populated values after conversion");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromJson20ToCsv20() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0));

        //THEN
        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "CSV should have header + data rows");
        assertTrue(lines[0].contains("STRUCTURE"), "CSV header should contain STRUCTURE column");
        // Regression guard for the OBS_VALUE-drop bug — see issue #49 / design 016.
        // Pre-fix this counted 0 populated rows for WEO.
        assertTrue(countPopulatedObsValueRows(csv) > 0,
                "OBS_VALUE column should have populated values after conversion");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromJson20ToXml30() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_XML_3_0_0));

        //THEN
        String xml = outputStream.toString(StandardCharsets.UTF_8);
        assertFalse(xml.isEmpty(), "Output should not be empty");
        assertTrue(xml.contains("DataSet"), "Output should contain DataSet element");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataWithEmptyDataset_noSeriesOrObservations() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_empty_dataset.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN + THEN — should not throw NPE or any other exception for empty dataset
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0)));
    }

    @Test
    @SneakyThrows
    void shouldPreserveObsValueWhenConvertingWeoJsonToCsv() {
        // Regression guard: WEO JSON-in -> CSV-out goes through the same flat-writer
        // path as BOP. Pre-fix this returned 0/51 — the existing
        // shouldConvertDataFromJson20ToCsv20 only checked header presence, so the
        // bug was silently affecting WEO too.
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");
        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0));

        assertEquals(51, countPopulatedObsValueRows(outputStream.toString(StandardCharsets.UTF_8)),
                "WEO JSON->CSV should preserve all 51 NGDP_RPCH OBS_VALUE rows");
    }

    @Test
    @SneakyThrows
    void shouldPreserveObsValueWhenConvertingBopJsonToCsv() {
        // Regression guard: BOP JSON-in -> CSV-out. Exercises the same flat-writer
        // path as CSV->CSV but with a different reader. Pre-fix this returned 0/90.
        // First generate the BOP JSON in-memory (same as the registry would emit
        // via JSON-out), then feed it back through JSON-in -> CSV-out.
        InputStream csvInput = getClass().getResourceAsStream("data_conversion/imf_bop_data.csv");
        SdmxBeans sdmxBeans = parseBopStructures();

        ByteArrayOutputStream jsonBuffer = new ByteArrayOutputStream();
        streamingDataConversionService.convert(csvInput, jsonBuffer, sdmxBeans, SdmxFormat.CSV_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        ByteArrayOutputStream csvOut = new ByteArrayOutputStream();
        streamingDataConversionService.convert(new java.io.ByteArrayInputStream(jsonBuffer.toByteArray()),
                csvOut, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0));

        assertEquals(90, countPopulatedObsValueRows(csvOut.toString(StandardCharsets.UTF_8)),
                "BOP JSON->CSV should preserve all 90 IMF-populated OBS_VALUE rows");
    }

    @Test
    @SneakyThrows
    void shouldPreserveObsValueWhenConvertingImfBopCsvToJson() {
        // Regression guard: BOP CSV-in -> JSON-out path. Pre-fix this still worked
        // (the JSON writer doesn't go through the buggy flat-data path), but kept
        // as a regression test in case the JSON path ever picks up the same shape.
        InputStream input = getClass().getResourceAsStream("data_conversion/imf_bop_data.csv");
        SdmxBeans sdmxBeans = parseBopStructures();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.CSV_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        assertEquals(90, countNonNullPrimaryMeasures(outputStream.toString(StandardCharsets.UTF_8)),
                "BOP CSV->JSON should preserve all 90 IMF-populated OBS_VALUE observations");
    }

    @Test
    @SneakyThrows
    void shouldRoundTripRealImfDipCsvWithAttributesAll() {
        // Regression guard for issue #39 Q1. Real IMF response for
        // GET /data/dataflow/IMF.STA/DIP/12.0.1/AUT+MLT.*.*.*.* with attributes=all.
        // Every data row in this response spans ~5 physical lines because
        // FULL_DESCRIPTION (and other long attributes) carry quoted multi-line
        // strings. Pre-fix the upstream reader threw
        // SdmxException("Line 2 has less elements than expected. Expected 49 ...
        // but line contained 41 elements") on the first data row.
        // Fixture is the trimmed first 30 rows of the live response (full file is
        // 4 MB; 30 rows is enough to exercise every quoted-newline path).
        InputStream input = getClass().getResourceAsStream("data_conversion/imf_dip_data.csv");
        SdmxBeans sdmxBeans = parseStructuresFixture("data_conversion/imf_dip_structures.json");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans,
                SdmxFormat.CSV_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0)));

        // Both the canonicalizer (fixes the read) and the copyDataToFlatWriter fix
        // (preserves OBS_VALUE) must work together for this to come out right.
        // The IMF source has 30 rows, of which the first one is dataset-metadata
        // (empty TIME_PERIOD/OBS_VALUE) and 6 are series-metadata rows; the rest
        // carry observation values.
        assertTrue(countPopulatedObsValueRows(outputStream.toString(StandardCharsets.UTF_8)) > 0,
                "Real DIP CSV with attributes=all should round-trip with at least one populated OBS_VALUE");
    }

    @Test
    @SneakyThrows
    void shouldRoundTripRealImfCpiCsvWithAttributesAll() {
        // Regression guard for issue #39 Q2. Real IMF response (~70 KB) for
        // GET /data/dataflow/IMF.STA/CPI/5.0.0/AUT.*.CP01+CP06.POP_PCH_PA_PT.Q with attributes=all.
        // Same root cause as Q1 (quoted multi-line attributes), so this is a sanity
        // check that the fix covers a second real dataflow rather than only DIP.
        InputStream input = getClass().getResourceAsStream("data_conversion/imf_cpi_data.csv");
        SdmxBeans sdmxBeans = parseStructuresFixture("data_conversion/imf_cpi_structures.json");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans,
                SdmxFormat.CSV_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0)));

        assertTrue(countPopulatedObsValueRows(outputStream.toString(StandardCharsets.UTF_8)) > 0,
                "Real CPI CSV with attributes=all should round-trip with at least one populated OBS_VALUE");
    }

    @Test
    @SneakyThrows
    void shouldReadCsvWithQuotedMultiLineFields() {
        // Regression guard for issue #39 / design 016. Upstream
        // CSVColumnReaderEngineImpl.moveNextRow uses BufferedReader.readLine() and
        // counts cells per physical line, so a CSV row whose quoted field spans
        // multiple physical lines triggers
        // SdmxException("Line N has less elements than expected ...").
        // The proxy now wraps CSV input with QuotedNewlineCanonicalizingInputStream
        // before handing the stream to the reader. This test pumps a synthetic
        // BOP-shaped CSV that embeds newlines inside FULL_DESCRIPTION through the
        // production path and asserts no exception + OBS_VALUE preserved.
        SdmxBeans sdmxBeans = parseBopStructures();

        // Build a tiny CSV using the real BOP header and two rows whose
        // FULL_DESCRIPTION value contains literal newlines inside quotes.
        // OBS_VALUE column index is 9; FULL_DESCRIPTION is index 11.
        String header = "STRUCTURE[;],STRUCTURE_ID,ACTION,COUNTRY,BOP_ACCOUNTING_ENTRY,INDICATOR,UNIT,FREQUENCY,TIME_PERIOD,OBS_VALUE,SCALE,FULL_DESCRIPTION";
        String row1 = "dataflow,IMF.STA:BOP(21.0.0),R,DEU,CD_T,G1,USD,A,2017,1230793709303.444,6,\"This is a long\nmulti-line description\nwith embedded newlines.\"";
        String row2 = "dataflow,IMF.STA:BOP(21.0.0),R,DEU,CD_T,G1,USD,A,2018,1500000000000.0,6,\"Another\r\nsplit value.\"";
        String csv = header + "\n" + row1 + "\n" + row2 + "\n";

        InputStream input = new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Pre-fix: this throws SdmxException("Line 2 has less elements than expected...").
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans,
                SdmxFormat.CSV_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0)));

        // Sanity-check both observations made it through. The canonicalizer
        // collapses the embedded newlines to spaces, so the writer emits well-formed
        // single-line rows with both OBS_VALUE values intact.
        assertEquals(2, countPopulatedObsValueRows(outputStream.toString(StandardCharsets.UTF_8)),
                "Both quoted multi-line rows should round-trip with OBS_VALUE preserved");
    }

    @Test
    @SneakyThrows
    void shouldPreserveObsValueWhenRoundTrippingImfBopCsv() {
        // Regression guard for issue #49 / design 016. BOP 21.0.0 CSV from IMF (DEU,
        // 2017) is fed back through the proxy's CSV-in -> CSV-out path. The IMF
        // source has 90 observation rows with OBS_VALUE populated and 24 rows with
        // empty OBS_VALUE (dataset-metadata-only rows). Pre-fix the round-trip
        // produced 0 populated rows.
        InputStream input = getClass().getResourceAsStream("data_conversion/imf_bop_data.csv");
        SdmxBeans sdmxBeans = parseBopStructures();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.CSV_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_CSV_2_0_0));

        assertEquals(90, countPopulatedObsValueRows(outputStream.toString(StandardCharsets.UTF_8)),
                "BOP CSV->CSV should preserve all 90 IMF-populated OBS_VALUE rows");
    }

    @Test
    @SneakyThrows
    void shouldConvertData_imf_fsic_seriesKeyed_withInlineDatasetAttributes() {
        //GIVEN
        // Series-keyed SDMX-JSON 2.0 response from IMF FSIC dataflow whose dataset-level
        // `attributes` array contains inline values (e.g. ["datahelp@imf.org"], ISO timestamps)
        // alongside indices. This previously crashed CustomSdmxJsonDataReaderEngineV2 with
        // StringIndexOutOfBoundsException because the dataset-level attributes array was
        // not skipped, causing the reader to misread an `observations` field inside the first
        // series and incorrectly set isFlat=true.
        InputStream input = getClass().getResourceAsStream("data_conversion/imf_fsic_data.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/imf_fsic_structures.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0)));

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode firstDataSet = jsonNode.path("data").path("dataSets").get(0);
        assertFalse(firstDataSet.isMissingNode(), "data.dataSets[0] should be present");

        JsonNode series = firstDataSet.path("series");
        assertTrue(series.isObject() && !series.isEmpty(), "series should be a non-empty object (series-keyed format)");

        JsonNode firstSeries = series.path("0:0:0:0");
        assertFalse(firstSeries.isMissingNode(), "series '0:0:0:0' should be present");
        JsonNode observations = firstSeries.path("observations");
        assertTrue(observations.isObject() && !observations.isEmpty(), "first series should have observations");
        // First observation's primary measure must not be null (sanity-check we actually read values, not just key shape).
        assertFalse(observations.get("0").get(0).isNull(), "first observation's primary measure should not be null");

        int totalObs = 0;
        for (JsonNode s : series) {
            totalObs += s.path("observations").size();
        }
        assertTrue(totalObs > 0, "total observations across series should be > 0");
    }

    @Test
    @SneakyThrows
    void shouldEmitRolesPluralOnTimeDimension_issue80() {
        // Issue #80 (#3): SDMX-JSON 2.0 schema defines `roles` (plural array of
        // strings) on dimensions/measures/attributes. sdmx-core emits `role`
        // (singular string-or-null). The custom V2 writer overrides this.
        SdmxBeans sdmxBeans = loadWeoStructures();
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_misroute_3countries.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        JsonNode root = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode obsDims = root.path("data").path("structures").get(0).path("dimensions").path("observation");
        JsonNode timeDim = obsDims.get(0);
        assertEquals("TIME_PERIOD", timeDim.path("id").asText(), "Observation dim should be TIME_PERIOD");
        assertTrue(timeDim.path("role").isMissingNode(), "Singular `role` field should not be emitted");
        JsonNode roles = timeDim.path("roles");
        assertTrue(roles.isArray(), "TIME_PERIOD should have `roles` array per SDMX-JSON 2.0 schema");
        boolean hasTime = false;
        for (JsonNode r : roles) {
            if ("time".equals(r.asText())) {
                hasTime = true;
                break;
            }
        }
        assertTrue(hasTime, "TIME_PERIOD `roles` array should contain \"time\"");
    }

    @Test
    @SneakyThrows
    void shouldEmitNonCodedTimePeriodValueShape_issue80() {
        // Issue #80 (#5): SDMX-JSON 2.0 schema allows two shapes for TIME_PERIOD
        // value entries -- coded {"id","name","start","end"} when the dimension
        // has an enumerated representation, or non-coded {"value":"1999"} for
        // ObservationalTimePeriod. The IMF WEO DSD declares TIME_PERIOD with a
        // text representation, so the non-coded shape applies. sdmx-core always
        // emits the coded shape with fabricated date bounds.
        SdmxBeans sdmxBeans = loadWeoStructures();
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_misroute_3countries.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        JsonNode root = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode timeValues = root.path("data").path("structures").get(0).path("dimensions").path("observation").get(0).path("values");
        assertTrue(timeValues.isArray() && !timeValues.isEmpty(), "TIME_PERIOD values should be a non-empty array");
        JsonNode firstValue = timeValues.get(0);
        assertFalse(firstValue.path("value").isMissingNode(), "TIME_PERIOD value entry should have `value` key (non-coded ObservationalTimePeriod)");
        assertTrue(firstValue.path("start").isMissingNode(), "Non-coded TIME_PERIOD must not fabricate `start` bound");
        assertTrue(firstValue.path("end").isMissingNode(), "Non-coded TIME_PERIOD must not fabricate `end` bound");
        assertTrue(firstValue.path("id").isMissingNode(), "Non-coded TIME_PERIOD must not emit `id`");
        assertTrue(firstValue.path("name").isMissingNode(), "Non-coded TIME_PERIOD must not emit `name`");
    }

    @SneakyThrows
    private SdmxBeans loadWeoStructures() {
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");
        return streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    @Test
    @SneakyThrows
    void shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80() {
        // Repro for issue #80 (#1): with the IMF WEO DSD (which has groups GROUP_INDICATOR
        // and GROUP_COUNTRY__INDICATOR), three series in the upstream IMF response get
        // silently relocated under a different INDICATOR code in the proxy output. The
        // 3-country trimmed sample (AGO, MAC, VEN) keeps the conditions that trigger
        // the misroute: MAC has GGX_NGDP but not GGXWDG_NGDP, and VEN has NGDPRPPPPC
        // and NGDP_RPCH but not NGDPRPC / NGDP_R respectively. Each "lost" series ends
        // up under the alphabetically-prior INDICATOR.
        //
        // Input: 111 series. Pre-fix expected output: 108 series (3 missing).
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_misroute_3countries.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        JsonNode root = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode firstDataSet = root.path("data").path("dataSets").get(0);
        JsonNode series = firstDataSet.path("series");
        JsonNode struct = root.path("data").path("structures").get(0);
        JsonNode dimsSeries = struct.path("dimensions").path("series");

        java.util.Map<String, java.util.List<String>> dimValues = new java.util.HashMap<>();
        for (JsonNode dim : dimsSeries) {
            java.util.List<String> codes = new java.util.ArrayList<>();
            for (JsonNode v : dim.path("values")) {
                codes.add(v.path("id").asText());
            }
            dimValues.put(dim.path("id").asText(), codes);
        }
        java.util.List<String> countries = dimValues.get("COUNTRY");
        java.util.List<String> indicators = dimValues.get("INDICATOR");
        java.util.List<String> frequencies = dimValues.get("FREQUENCY");
        java.util.Set<java.util.List<String>> outputLabels = new java.util.HashSet<>();
        for (String key : series.propertyNames()) {
            String[] parts = key.split(":");
            outputLabels.add(java.util.List.of(
                    countries.get(Integer.parseInt(parts[0])),
                    indicators.get(Integer.parseInt(parts[1])),
                    frequencies.get(Integer.parseInt(parts[2]))));
        }

        assertEquals(111, series.size(), "All 111 input series should be preserved (currently misroutes 3)");
        assertTrue(outputLabels.contains(java.util.List.of("MAC", "GGX_NGDP", "A")),
                "(MAC, GGX_NGDP, A) should be present (currently misrouted to GGXWDG_NGDP)");
        assertTrue(outputLabels.contains(java.util.List.of("VEN", "NGDPRPPPPC", "A")),
                "(VEN, NGDPRPPPPC, A) should be present (currently misrouted to NGDPRPC)");
        assertTrue(outputLabels.contains(java.util.List.of("VEN", "NGDP_RPCH", "A")),
                "(VEN, NGDP_RPCH, A) should be present (currently misrouted to NGDP_R)");
    }

    // ---- helpers used by the issue #49 regression tests ----

    @SneakyThrows
    private SdmxBeans parseBopStructures() {
        return parseStructuresFixture("data_conversion/imf_bop_structures.json");
    }

    @SneakyThrows
    private SdmxBeans parseStructuresFixture(String resourcePath) {
        InputStream structures = getClass().getResourceAsStream(resourcePath);
        return streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    /**
     * Counts CSV rows with a non-empty OBS_VALUE cell. Uses a crude split because
     * the OBS_VALUE column is numeric/unquoted; quoted multi-line cells (long
     * descriptions) come later in the row and don't affect the count.
     */
    private static int countPopulatedObsValueRows(String csv) {
        String[] lines = csv.split("\\R");
        if (lines.length == 0) return 0;
        String[] header = lines[0].split(",", -1);
        int obsIdx = -1;
        for (int i = 0; i < header.length; i++) {
            if ("OBS_VALUE".equals(header[i].trim())) { obsIdx = i; break; }
        }
        if (obsIdx < 0) return 0;
        int filled = 0;
        for (int li = 1; li < lines.length; li++) {
            if (lines[li].isEmpty()) continue;
            String[] cells = lines[li].split(",", -1);
            if (obsIdx < cells.length && !cells[obsIdx].trim().isEmpty()) filled++;
        }
        return filled;
    }

    @SneakyThrows
    private static int countNonNullPrimaryMeasures(String json) {
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode dataSets = root.path("data").path("dataSets");
        int count = 0;
        if (dataSets.isArray() && !dataSets.isEmpty()) {
            for (JsonNode s : dataSets.get(0).path("series")) {
                for (JsonNode o : s.path("observations")) {
                    if (o.isArray() && !o.isEmpty() && !o.get(0).isNull()) count++;
                }
            }
        }
        return count;
    }
}
