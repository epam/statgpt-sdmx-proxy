package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
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
import java.util.Map;

@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class StreamingStructureConversionServiceTest {
    @Autowired
    private StreamingStructureConversionService sut;
    @Autowired
    private StructureFixtureService fixtureService;

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_AllDsds_Detail_FULL() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);


        FixtureConfiguration metadataAttributeFixture = new FixtureConfiguration();
        metadataAttributeFixture.setType(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE);
        metadataAttributeFixture.setConfig(new HashMap<>());

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(metadataAttributeFixture, versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_AllDsds_Detail_FULL_References_ALL() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full_references_all.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);


        FixtureConfiguration dsdAttributeFixture = new FixtureConfiguration();
        dsdAttributeFixture.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        dsdAttributeFixture.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(dsdAttributeFixture, versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_Dsd_NoDimension() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_no_dimension.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);

        //WHEN
        sut.convert(input, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_IMF_STA_DSD_CO2E_2_0_0_SdmxSemanticException_1207_1208_shouldNotBeThrown_whenPatched() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_IMF.STA_DSD_CO2E_2.0.0_SdmxSemanticException_1207_1208.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_SURVEY_CONTACTS_WildcardException_shouldNotBeThrown_whenPatched() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_SURVEY_CONTACTS_WildcardException.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.VERSION_WILDCARD);
        fc.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_SomeSurveyDsd_NPE_On_MissingPrimaryMeasure_shouldNotBeThrown() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_IMF.STA.DS_SURVEYS_CAMPAIGN_ANSWERS_WITH_IMF_STA_DS_CL_DQAF_COUNTRY_SAMPLE_1_0_0_SUBJECT_ID_6.1.0_EmptyPrimaryMeasure.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);


        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.VERSION_WILDCARD);
        fc.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_toXML_2_1_Imf_3_0_AllDsds_Detail_FULL_cannotCreateXmlWrite() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_XML_2_1_VALUE);

        FixtureConfiguration dsdAttributeFixture = new FixtureConfiguration();
        dsdAttributeFixture.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        dsdAttributeFixture.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(dsdAttributeFixture, versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

    }

}
