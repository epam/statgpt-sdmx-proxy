package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.FixtureType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.fixture.FixtureService;
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
import java.util.HashMap;
import java.util.List;

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
    private FixtureService fixtureService;


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
        metadataUsageFixture.setType(FixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0,
                List.of(metadataUsageFixture));

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
        metadataUsageFixture.setType(FixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataUsageFixture.setConfig(new HashMap<>());

        InputStream fixedStructures = fixtureService.applyFixtures(structures, ReturnFormat.JSON_STRUCTURE_2_0_0,
                List.of(metadataUsageFixture));

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(fixedStructures, ReturnFormat.JSON_STRUCTURE_2_0_0);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        //WHEN
        streamingDataConversionService.convert(input, outputStream, sdmxBeans, ReturnFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaType.SDMX_JSON_2_0_0_VALUE));

    }
}
