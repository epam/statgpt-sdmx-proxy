package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.services.adapter.GenericRegistryAdapter;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataAttributeUsageFolderTest {

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

    private final GenericRegistryAdapter adapter = mock(GenericRegistryAdapter.class);
    private final MetadataAttributeUsageFolder folder = new MetadataAttributeUsageFolder(adapter, MAPPER);

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** MSD is inline, so no side-fetch happens and the query argument is unused. */
    @SneakyThrows
    private JsonNode foldInline(String json) {
        return MAPPER.readTree(folder.foldForXml21(bytes(json), null));
    }

    @Test
    @SneakyThrows
    void shouldFoldUsagesIntoAttributes() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");

        assertEquals(3, attributes.size(), "Should have 1 existing + 2 folded attributes");
        assertNotNull(findById(attributes, "DOI"), "DOI attribute should exist");
        assertNotNull(findById(attributes, "AUTHOR"), "AUTHOR attribute should exist");
    }

    @Test
    @SneakyThrows
    void shouldSetConceptIdentityFromMsd() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        assertEquals("urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=TEST:CS_MASTER(1.0.0).DOI", findById(attributes, "DOI").path("conceptIdentity").asText());
    }

    @Test
    @SneakyThrows
    void shouldSetIsMandatoryFromMinOccurs() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        assertTrue(findById(attributes, "DOI").path("isMandatory").asBoolean(), "DOI has minOccurs=1, should be mandatory");
        assertFalse(findById(attributes, "AUTHOR").path("isMandatory").asBoolean(), "AUTHOR has minOccurs=0, should not be mandatory");
    }

    @Test
    @SneakyThrows
    void shouldPreserveAnnotationsFromUsage() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        JsonNode annotations = findById(attributes, "DOI").path("annotations");
        assertEquals(1, annotations.size());
        assertEquals("note", annotations.get(0).path("id").asText());
        assertEquals("test annotation", annotations.get(0).path("value").asText());
    }

    @Test
    @SneakyThrows
    void shouldPreserveAttributeRelationshipFromUsage() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        assertTrue(findById(attributes, "DOI").path("attributeRelationship").has("none"), "attributeRelationship should be preserved from usage");
    }

    @Test
    @SneakyThrows
    void shouldHaveEmptyConceptRoles() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        JsonNode doi = findById(attributes, "DOI");
        assertTrue(doi.path("conceptRoles").isArray());
        assertEquals(0, doi.path("conceptRoles").size());
    }

    @Test
    @SneakyThrows
    void shouldRemoveMetadataAttributeUsagesAfterFolding() {
        JsonNode attributeList = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");
        assertTrue(attributeList.path("metadataAttributeUsages").isMissingNode(), "metadataAttributeUsages should be removed after folding");
    }

    @Test
    @SneakyThrows
    void shouldRemoveMetadataStructuresSoTheyDoNotReachConverter() {
        assertTrue(foldInline(FULL_JSON).path("data").path("metadataStructures").isMissingNode(), "metadataStructures must be stripped before conversion (client requested only the DSD)");
    }

    @Test
    @SneakyThrows
    void shouldPreserveExistingAttributes() {
        JsonNode attributes = foldInline(FULL_JSON).path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList").path("attributes");
        JsonNode existing = findById(attributes, "EXISTING_ATTR");
        assertNotNull(existing, "Existing attribute should be preserved");
        assertTrue(existing.path("attributeRelationship").has("observation"));
    }

    @Test
    @SneakyThrows
    void shouldLeaveUsagesWhenMsdCannotBeResolved() {
        // No inline MSD; side-fetch returns nothing -> usages left in place (dropped later by converter).
        when(adapter.getStructures(any())).thenReturn(null);
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
                              { "annotations": [], "metadataAttributeReference": "FOO", "attributeRelationship": { "none": {} } }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
                """;
        JsonNode root = MAPPER.readTree(folder.foldForXml21(bytes(json), TranslatedStructureQuery.builder().build()));
        JsonNode attributeList = root.path("data").path("dataStructures").get(0)
                .path("dataStructureComponents").path("attributeList");
        assertEquals(0, attributeList.path("attributes").size(), "No attributes should be added when MSD is missing");
        assertTrue(attributeList.has("metadataAttributeUsages"), "metadataAttributeUsages should remain when MSD is missing");
    }

    @Test
    @SneakyThrows
    void shouldHandleJsonWithNoDataStructures() {
        JsonNode root = foldInline("""
                {"data": {"codelists": []}}
                """);
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
                              { "id": "ATTR1", "conceptIdentity": "urn:test", "isMandatory": false, "conceptRoles": [], "attributeRelationship": { "observation": {} } }
                            ]
                          }
                        }
                      }
                    ]
                  }
                }
                """;
        JsonNode attributes = foldInline(json).path("data").path("dataStructures").get(0)
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
