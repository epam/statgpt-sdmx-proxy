package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageFolder;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class StreamingStructureConversionServiceTest {
    @Autowired
    private StreamingStructureConversionService sut;
    @Autowired
    private StructureFixtureService fixtureService;
    @Autowired
    private MetadataAttributeUsagePreserver metadataAttributeUsagePreserver;
    @Autowired
    private MetadataAttributeUsageFolder metadataAttributeUsageFolder;

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_AllDsds_Detail_FULL() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_AllDsds_Detail_FULL_References_ALL() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full_references_all.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);


        FixtureConfiguration dsdAttributeFixture = new FixtureConfiguration();
        dsdAttributeFixture.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        dsdAttributeFixture.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(dsdAttributeFixture, versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_Dsd_NoDimension() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_no_dimension.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        //WHEN
        sut.convert(input, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_IMF_STA_DSD_CO2E_2_0_0_SdmxSemanticException_1207_1208_shouldNotBeThrown_whenPatched() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_IMF.STA_DSD_CO2E_2.0.0_SdmxSemanticException_1207_1208.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_SURVEY_CONTACTS_WildcardException_shouldNotBeThrown_whenPatched() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_SURVEY_CONTACTS_WildcardException.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.VERSION_WILDCARD);
        fc.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_SomeSurveyDsd_NPE_On_MissingPrimaryMeasure_shouldNotBeThrown() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_IMF.STA.DS_SURVEYS_CAMPAIGN_ANSWERS_WITH_IMF_STA_DS_CL_DQAF_COUNTRY_SAMPLE_1_0_0_SUBJECT_ID_6.1.0_EmptyPrimaryMeasure.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);


        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(StructureFixtureType.VERSION_WILDCARD);
        fc.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(fc);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());

    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Imf_3_0_Hierarchy_Detail_FULL_References_DESCENDANTS() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/hierarchy_detail_full_references_descendants.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode dataNode = jsonNode.get("data");
        assertNotNull(dataNode, "data node must be present");

        // Hierarchies must be present in output
        JsonNode hierarchies = dataNode.get("hierarchies");
        assertNotNull(hierarchies, "hierarchies must be present in output");
        assertTrue(hierarchies.isArray() && !hierarchies.isEmpty(), "hierarchies must be a non-empty array");

        // Verify the specific hierarchy
        JsonNode hierarchy = hierarchies.get(0);
        assertEquals("H_BOP_BOP_AGG_ANALYTIC_PRESENTATION", hierarchy.get("id").asText());
        assertEquals("IMF.STA", hierarchy.get("agencyID").asText());
        assertEquals("1.13.0", hierarchy.get("version").asText());

        // Hierarchical codes must be present
        JsonNode hierarchicalCodes = hierarchy.get("hierarchicalCodes");
        assertNotNull(hierarchicalCodes, "hierarchicalCodes must be present");
        assertTrue(hierarchicalCodes.isArray() && !hierarchicalCodes.isEmpty(), "hierarchicalCodes must be a non-empty array");

        // Verify first hierarchical code has code reference and level
        JsonNode firstCode = hierarchicalCodes.get(0);
        assertNotNull(firstCode.get("id"), "hierarchical code must have an id");
        assertNotNull(firstCode.get("code"), "hierarchical code must have a code reference");

        // IMF hierarchy uses plain level IDs ("0", "1", etc.) — these must be preserved
        boolean hasLevels = false;
        for (JsonNode code : hierarchicalCodes) {
            if (code.has("level") && code.get("level").isTextual()) {
                hasLevels = true;
                break;
            }
        }
        assertTrue(hasLevels, "hierarchical codes must have level references preserved");

        // Verify nested hierarchical codes are preserved
        boolean hasNestedCodes = false;
        for (JsonNode code : hierarchicalCodes) {
            if (code.has("hierarchicalCodes") && !code.get("hierarchicalCodes").isEmpty()) {
                hasNestedCodes = true;
                break;
            }
        }
        assertTrue(hasNestedCodes, "hierarchy must contain nested hierarchical codes");

        // Codelists from references=descendants must also be present
        JsonNode codelists = dataNode.get("codelists");
        assertNotNull(codelists, "codelists from descendants must be present");
        assertTrue(codelists.isArray() && !codelists.isEmpty(), "codelists must be a non-empty array");
    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_Bis_3_0_Hierarchy_Detail_FULL_References_DESCENDANTS() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/bis/3.0/hierarchy_detail_full_references_descendants.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        //WHEN
        sut.convert(input, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode dataNode = jsonNode.get("data");
        assertNotNull(dataNode, "data node must be present");

        // Hierarchies must be present in output
        JsonNode hierarchies = dataNode.get("hierarchies");
        assertNotNull(hierarchies, "hierarchies must be present in output");
        assertTrue(hierarchies.isArray() && !hierarchies.isEmpty(), "hierarchies must be a non-empty array");

        // Verify the specific hierarchy
        JsonNode hierarchy = hierarchies.get(0);
        assertEquals("BANKTYPE@LBS_CLIENT_HIERARCHIES", hierarchy.get("id").asText());
        assertEquals("BIS.LBS", hierarchy.get("agencyID").asText());
        assertEquals("1.0", hierarchy.get("version").asText());

        // Hierarchical codes must be present
        JsonNode hierarchicalCodes = hierarchy.get("hierarchicalCodes");
        assertNotNull(hierarchicalCodes, "hierarchicalCodes must be present");
        assertTrue(hierarchicalCodes.isArray() && !hierarchicalCodes.isEmpty(), "hierarchicalCodes must be a non-empty array");

        // Verify hierarchical codes have code references
        JsonNode firstCode = hierarchicalCodes.get(0);
        assertNotNull(firstCode.get("id"), "hierarchical code must have an id");
        assertNotNull(firstCode.get("code"), "hierarchical code must have a code reference");

        // Verify nested hierarchical codes are preserved
        boolean hasNestedCodes = false;
        for (JsonNode code : hierarchicalCodes) {
            if (code.has("hierarchicalCodes") && !code.get("hierarchicalCodes").isEmpty()) {
                hasNestedCodes = true;
                break;
            }
        }
        assertTrue(hasNestedCodes, "hierarchy must contain nested hierarchical codes");

        // Codelists from references=descendants must also be present
        JsonNode codelists = dataNode.get("codelists");
        assertNotNull(codelists, "codelists from descendants must be present");
        assertTrue(codelists.isArray() && !codelists.isEmpty(), "codelists must be a non-empty array");
    }

    @Test
    @SneakyThrows
    void shouldPreserveDsdMetadataConceptRolesAnnotationValueAndUsages_issue79() {
        //GIVEN
        byte[] rawBytes;
        try (InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_weo_issue_79.json")) {
            assertNotNull(input, "test resource dsd_weo_issue_79.json must be present");
            rawBytes = input.readAllBytes();
        }

        FixtureConfiguration preserveFixture = new FixtureConfiguration();
        preserveFixture.setType(StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES);
        preserveFixture.setConfig(new HashMap<>());

        FixtureConfiguration annotationValueFixture = new FixtureConfiguration();
        annotationValueFixture.setType(StructureFixtureType.ANNOTATION_VALUE_TO_TEXT);
        annotationValueFixture.setConfig(new HashMap<>());

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs =
                List.of(preserveFixture, annotationValueFixture, versionWildcardFixture);

        Map<String, com.fasterxml.jackson.databind.JsonNode> capturedUsages = metadataAttributeUsagePreserver.capture(rawBytes);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);
        InputStream fixedInputStream = fixtureService.applyFixtures(
                new java.io.ByteArrayInputStream(rawBytes), SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);
        byte[] convertedBytes = metadataAttributeUsagePreserver.inject(outputStream.toByteArray(), capturedUsages);

        //THEN
        JsonNode root = new ObjectMapper().readTree(convertedBytes);
        JsonNode dsd = root.path("data").path("dataStructures").get(0);
        assertNotNull(dsd, "dataStructures[0] must be present");

        // Issue 3: DSD metadata URN preserved
        String metadataUrn = dsd.path("metadata").asText();
        assertTrue(metadataUrn.contains("MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL"),
                "DSD metadata URN must be preserved, got: " + metadataUrn);

        // Issue 4: FREQUENCY dimension conceptRoles preserved
        JsonNode dimensions = dsd.path("dataStructureComponents").path("dimensionList").path("dimensions");
        assertTrue(dimensions.isArray() && !dimensions.isEmpty(), "dimensions must be a non-empty array");
        JsonNode frequency = null;
        for (JsonNode dim : dimensions) {
            if ("FREQUENCY".equals(dim.path("id").asText())) {
                frequency = dim;
                break;
            }
        }
        assertNotNull(frequency, "FREQUENCY dimension must be present");
        JsonNode conceptRoles = frequency.path("conceptRoles");
        assertTrue(conceptRoles.isArray() && conceptRoles.size() == 1,
                "FREQUENCY conceptRoles must contain one entry, got: " + conceptRoles);
        assertTrue(conceptRoles.get(0).asText().endsWith("SDMX_CONCEPT_ROLES(1.0).FREQ"),
                "FREQUENCY conceptRoles[0] must end with SDMX_CONCEPT_ROLES(1.0).FREQ, got: " + conceptRoles.get(0).asText());

        // Issue 1: annotation `value` survives (rewritten as `text`)
        JsonNode annotations = dsd.path("annotations");
        assertTrue(annotations.isArray() && !annotations.isEmpty(), "annotations must be non-empty");
        JsonNode origin = null;
        for (JsonNode a : annotations) {
            if ("origin".equals(a.path("id").asText())) {
                origin = a;
                break;
            }
        }
        assertNotNull(origin, "origin annotation must be present");
        assertEquals("INTEGRATION", origin.path("text").asText(),
                "origin annotation text must carry value=INTEGRATION (rewritten from `value`)");

        // Issue 2: metadataAttributeUsages restored on the attribute list
        JsonNode usages = dsd.path("dataStructureComponents").path("attributeList").path("metadataAttributeUsages");
        assertTrue(usages.isArray() && !usages.isEmpty(),
                "metadataAttributeUsages must be restored as a non-empty array");
    }

    @Test
    @SneakyThrows
    void shouldConvertStructures_toXML_2_1_Imf_3_0_AllDsds_Detail_FULL_cannotCreateXmlWrite() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("structure_conversion/imf/3.0/dsd_detail_full.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_XML_2_1);

        FixtureConfiguration dsdAttributeFixture = new FixtureConfiguration();
        dsdAttributeFixture.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        dsdAttributeFixture.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        FixtureConfiguration versionWildcardFixture = new FixtureConfiguration();
        versionWildcardFixture.setType(StructureFixtureType.VERSION_WILDCARD);
        versionWildcardFixture.setConfig(new HashMap<>());

        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs = List.of(dsdAttributeFixture, versionWildcardFixture);
        InputStream fixedInputStream = fixtureService.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, fixtureConfigs);

        //WHEN
        sut.convert(fixedInputStream, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

    }

    @Test
    @SneakyThrows
    void shouldFoldMetadataAttributeUsagesIntoAttributes_forXml21() {
        //GIVEN: IMF QNEA DSD (18 attributes + 25 metadataAttributeUsages) with its MSD inline,
        // as AdapterRouterImpl hands it to the folder on the XML 2.1 path (design 032). The MSD is
        // already present, so no side-fetch happens and the folder's adapter is never invoked.
        byte[] input = getClass().getResourceAsStream("structure_conversion/imf/3.0/qnea_dsd_with_msd.json").readAllBytes();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_XML_2_1);

        //WHEN: fold usages into attributes, then convert to 2.1 XML
        byte[] folded = metadataAttributeUsageFolder.foldForXml21(input, null);
        sut.convert(new java.io.ByteArrayInputStream(folded), outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        String xml = outputStream.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("id=\"DOI\""), "folded metadata attribute DOI must appear as a DataAttribute");
        assertTrue(xml.contains("id=\"AUTHOR\""), "folded metadata attribute AUTHOR must appear as a DataAttribute");
        int attrCount = xml.split("<str:Attribute ", -1).length - 1;
        assertEquals(43, attrCount, "AttributeList must contain 18 real + 25 folded attributes (matches IMF native 2.1)");
        assertFalse(xml.contains("<str:MetadataStructure"), "the inline MSD must not leak into the DSD-only response");
    }

}
