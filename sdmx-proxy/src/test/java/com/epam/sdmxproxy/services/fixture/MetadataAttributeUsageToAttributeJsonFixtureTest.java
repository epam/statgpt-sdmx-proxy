package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageToAttributeJsonFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataAttributeUsageToAttributeJsonFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FULL_JSON = """
            {
              "data": {
                "dataStructures": [
                  {
                    "id": "DSD_TEST",
                    "agencyID": "TEST.AGENCY",
                    "version": "1.0.0",
                    "metadata": "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=TEST.AGENCY:MSD_TEST(1.0+.0)",
                    "dataStructureComponents": {
                      "attributeList": {
                        "annotations": [],
                        "id": "AttributeDescriptor",
                        "attributes": [
                          {
                            "annotations": [],
                            "id": "EXISTING_ATTR",
                            "conceptIdentity": "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=TEST:CS(1.0.0).EXISTING_ATTR",
                            "isMandatory": false,
                            "conceptRoles": [],
                            "attributeRelationship": { "observation": {} }
                          }
                        ],
                        "metadataAttributeUsages": [
                          {
                            "annotations": [{"id": "note", "value": "test annotation"}],
                            "metadataAttributeReference": "DOI",
                            "attributeRelationship": { "none": {} }
                          },
                          {
                            "annotations": [],
                            "metadataAttributeReference": "AUTHOR",
                            "attributeRelationship": { "none": {} }
                          }
                        ]
                      }
                    }
                  }
                ],
                "metadataStructures": [
                  {
                    "id": "MSD_TEST",
                    "agencyID": "TEST.AGENCY",
                    "version": "1.0.0",
                    "metadataStructureComponents": {
                      "metadataAttributeList": {
                        "annotations": [],
                        "metadataAttributes": [
                          {
                            "id": "DOI",
                            "conceptIdentity": "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=TEST:CS_MASTER(1.0.0).DOI",
                            "maxOccurs": 1,
                            "minOccurs": 1,
                            "isPresentational": false
                          },
                          {
                            "id": "AUTHOR",
                            "conceptIdentity": "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=TEST:CS_MASTER(1.0.0).AUTHOR",
                            "maxOccurs": 1,
                            "minOccurs": 0,
                            "isPresentational": false
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """;
    private final MetadataAttributeUsageToAttributeJsonFixture fixture =
            new MetadataAttributeUsageToAttributeJsonFixture();

    private InputStream toStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReturnCorrectType() {
        assertEquals(StructureFixtureType.METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE, fixture.getType());
    }

    @Test
    void shouldSupportJsonStructure() {
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.JSON_STRUCTURE_2_0_0));
        assertEquals(1, fixture.supportedFormats().size());
    }

    @Test
    @SneakyThrows
    void shouldConvertUsagesToAttributes() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributeList = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");
        JsonNode attributes = attributeList.path("attributes");

        assertEquals(3, attributes.size(), "Should have 1 existing + 2 converted attributes");

        JsonNode doi = findById(attributes, "DOI");
        assertNotNull(doi, "DOI attribute should exist");

        JsonNode author = findById(attributes, "AUTHOR");
        assertNotNull(author, "AUTHOR attribute should exist");
    }

    @Test
    @SneakyThrows
    void shouldSetConceptIdentityFromMsd() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode doi = findById(attributes, "DOI");
        assertEquals(
                "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=TEST:CS_MASTER(1.0.0).DOI",
                doi.path("conceptIdentity").asText()
        );
    }

    @Test
    @SneakyThrows
    void shouldSetIsMandatoryFromMinOccurs() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode doi = findById(attributes, "DOI");
        assertTrue(doi.path("isMandatory").asBoolean(), "DOI has minOccurs=1, should be mandatory");

        JsonNode author = findById(attributes, "AUTHOR");
        assertFalse(author.path("isMandatory").asBoolean(), "AUTHOR has minOccurs=0, should not be mandatory");
    }

    @Test
    @SneakyThrows
    void shouldPreserveAnnotationsFromUsage() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode doi = findById(attributes, "DOI");
        JsonNode annotations = doi.path("annotations");
        assertEquals(1, annotations.size());
        assertEquals("note", annotations.get(0).path("id").asText());
        assertEquals("test annotation", annotations.get(0).path("value").asText());
    }

    @Test
    @SneakyThrows
    void shouldPreserveAttributeRelationshipFromUsage() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode doi = findById(attributes, "DOI");
        assertTrue(doi.path("attributeRelationship").has("none"),
                "attributeRelationship should be preserved from usage");
    }

    @Test
    @SneakyThrows
    void shouldHaveEmptyConceptRoles() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode doi = findById(attributes, "DOI");
        assertTrue(doi.path("conceptRoles").isArray());
        assertEquals(0, doi.path("conceptRoles").size());
    }

    @Test
    @SneakyThrows
    void shouldRemoveMetadataAttributeUsagesAfterConversion() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributeList = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");

        assertTrue(attributeList.path("metadataAttributeUsages").isMissingNode(),
                "metadataAttributeUsages should be removed after conversion");
    }

    @Test
    @SneakyThrows
    void shouldPreserveExistingAttributes() {
        InputStream result = fixture.apply(toStream(FULL_JSON), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        JsonNode existing = findById(attributes, "EXISTING_ATTR");
        assertNotNull(existing, "Existing attribute should be preserved");
        assertTrue(existing.path("attributeRelationship").has("observation"));
    }

    @Test
    @SneakyThrows
    void shouldSkipDsdWhenMsdNotInJson() {
        String json = """
                {
                  "data": {
                    "dataStructures": [
                      {
                        "id": "DSD_ORPHAN",
                        "metadata": "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=UNKNOWN:MSD_MISSING(1.0.0)",
                        "dataStructureComponents": {
                          "attributeList": {
                            "attributes": [],
                            "metadataAttributeUsages": [
                              {
                                "annotations": [],
                                "metadataAttributeReference": "FOO",
                                "attributeRelationship": { "none": {} }
                              }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        InputStream result = fixture.apply(toStream(json), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributeList = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");

        assertEquals(0, attributeList.path("attributes").size(),
                "No attributes should be added when MSD is missing");
        assertTrue(attributeList.has("metadataAttributeUsages"),
                "metadataAttributeUsages should remain when MSD is missing");
    }

    @Test
    @SneakyThrows
    void shouldHandleJsonWithNoDataStructures() {
        String json = """
                {"data": {"codelists": []}}
                """;

        InputStream result = fixture.apply(toStream(json), Map.of());
        JsonNode root = MAPPER.readTree(result);

        assertTrue(root.path("data").has("codelists"));
    }

    @Test
    @SneakyThrows
    void shouldHandleDsdWithNoMetadataField() {
        String json = """
                {
                  "data": {
                    "dataStructures": [
                      {
                        "id": "DSD_NO_META",
                        "dataStructureComponents": {
                          "attributeList": {
                            "attributes": [
                              {
                                "id": "ATTR1",
                                "conceptIdentity": "urn:test",
                                "isMandatory": false,
                                "conceptRoles": [],
                                "attributeRelationship": { "observation": {} }
                              }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        InputStream result = fixture.apply(toStream(json), Map.of());
        JsonNode root = MAPPER.readTree(result);

        JsonNode attributes = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        assertEquals(1, attributes.size(), "Existing attributes should be untouched");
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }
}
