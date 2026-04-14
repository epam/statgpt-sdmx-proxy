package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
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
import java.util.HashMap;
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
    private StructureFixtureService fixtureService;

    @Autowired
    private ObjectMapper objectMapper;


    @Test
    @SneakyThrows
    void shouldConvertDataWithoutLosingObservations() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
        InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, ReturnFormat.XML_2_1);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE));

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

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0,
                fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE));

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode dataNode = jsonNode.path("data");
        JsonNode dataSets = dataNode.path("dataSets");
        JsonNode firstDataSet = dataSets.get(0);

        // All observations are present
        JsonNode observations = firstDataSet.path("series").path("0:0:0").get("observations");
        for (int i = 0; i < observations.size(); i++) {
            assertFalse(observations.get(String.valueOf(i)).get(0).isNull());
        }

        // Dataset-level attributes are present and indexed in output
        JsonNode dsAttributes = firstDataSet.path("attributes");
        assertFalse(dsAttributes.isMissingNode(), "dataSets[0].attributes should be present");
        assertTrue(dsAttributes.isArray(), "dataSets[0].attributes should be an array");

        // FULL_DESCRIPTION should appear in output structure and be indexed in dataSets
        JsonNode structuresNode = dataNode.path("structures");
        assertFalse(structuresNode.isMissingNode() || structuresNode.isEmpty(), "data.structures should be present");
        JsonNode firstStructure = structuresNode.get(0);
        JsonNode attributesNode = firstStructure.path("attributes");
        JsonNode dataSetAttributes = attributesNode.path("dataset");
        boolean hasFullDescription = false;
        for (JsonNode attr : dataSetAttributes) {
            if ("FULL_DESCRIPTION".equals(attr.path("id").asText())) {
                hasFullDescription = true;
                break;
            }
        }
        assertTrue(hasFullDescription, "FULL_DESCRIPTION should appear in output structure attributes");
        // Attributes in dataSets should be integers (indexed) - at least some elements
        boolean hasIndexedAttribute = false;
        for (JsonNode attrVal : dsAttributes) {
            if (attrVal.isNumber() && !attrVal.isNull()) {
                hasIndexedAttribute = true;
                break;
            }
        }
        assertTrue(hasIndexedAttribute, "dataSets[0].attributes should contain indexed (integer) values");
    }

    @Test
    @SneakyThrows
    void shouldConvertData_ime_res_weo_no_query_params_in_proxy() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_no_query_params_to_proxy.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0,
                fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE));

    }


    @Test
    @SneakyThrows
    void shouldConvertData_ime_res_weo_without_adding_new_series_attributes_and_reseting_obs_indexes() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/data_weo_2026-2028_period.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0,
                fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE));

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
        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, ReturnFormat.XML_2_1);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1, MediaType.valueOf(SdmxMediaType.SDMX_XML_3_0_0_VALUE));

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
        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, ReturnFormat.XML_2_1);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1, MediaType.valueOf(SdmxMediaType.SDMX_CSV_2_0_0_VALUE));

        //THEN
        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "CSV should have header + data rows");
        assertTrue(lines[0].contains("STRUCTURE"), "CSV header should contain STRUCTURE column");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromJson20ToCsv20() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_CSV_2_0_0_VALUE));

        //THEN
        String csv = outputStream.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "CSV should have header + data rows");
        assertTrue(lines[0].contains("STRUCTURE"), "CSV header should contain STRUCTURE column");
    }

    @Test
    @SneakyThrows
    void shouldConvertDataFromJson20ToXml30() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("data_conversion/NGDP_RPCH_currentStructureIndex_Null.json");
        InputStream structures = getClass().getResourceAsStream("data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_XML_3_0_0_VALUE));

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

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN + THEN — should not throw NPE or any other exception for empty dataset
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE)));
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

        FixtureConfiguration metadataUsageFixture = new FixtureConfiguration();
        metadataUsageFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataUsageFixture);
        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        assertDoesNotThrow(() -> streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE)));

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
}
