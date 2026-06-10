package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageXmlInjector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataAttributeUsageXmlInjectorTest {

    private static final String STRUCTURE_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v3_0/structure";
    private static final String KEY = "IMF.STA|DSD_QNEA|7.0.0";

    private final MetadataAttributeUsageXmlInjector sut = new MetadataAttributeUsageXmlInjector();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SneakyThrows
    void injectsNativeUsages_dimensionsRelationship() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("TOPIC", dimensions("INDICATOR", "PRICE_TYPE")))));

        Element usage = onlyUsage(out);
        assertEquals("TOPIC", childText(usage, "MetadataAttributeReference"));
        Element relationship = child(usage, "AttributeRelationship");
        List<Element> dims = children(relationship, "Dimension");
        assertEquals(2, dims.size(), "two Dimension elements expected");
        assertEquals("INDICATOR", dims.get(0).getTextContent());
        assertEquals("PRICE_TYPE", dims.get(1).getTextContent());
    }

    @Test
    @SneakyThrows
    void injectsNativeUsages_observationRelationship() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("ACCESS_SHARING_LEVEL", relationship("observation")))));

        Element relationship = child(onlyUsage(out), "AttributeRelationship");
        assertNotNull(child(relationship, "Observation"), "Observation element expected");
    }

    @Test
    @SneakyThrows
    void injectsNativeUsages_noneRelationship_emitsDataflow() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        Element relationship = child(onlyUsage(out), "AttributeRelationship");
        assertNotNull(child(relationship, "Dataflow"), "none must be encoded as Dataflow");
    }

    @Test
    @SneakyThrows
    void injectsNativeUsages_missingRelationship_fallsBackToDataflow() {
        ObjectNode usage = mapper.createObjectNode();
        usage.put("metadataAttributeReference", "DOI");
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage)));

        Element relationship = child(onlyUsage(out), "AttributeRelationship");
        assertNotNull(relationship, "AttributeRelationship is XSD-required and must always be emitted");
        assertNotNull(child(relationship, "Dataflow"), "missing relationship falls back to Dataflow");
    }

    @Test
    @SneakyThrows
    void synthesizesUrnFromDsdCoordinates() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        Element usage = onlyUsage(out);
        assertEquals("urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataAttribute=IMF.STA:DSD_QNEA(7.0.0).DOI", usage.getAttribute("urn"));
    }

    @Test
    @SneakyThrows
    void placesUsagesBeforeRegularAttributes() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        Element attributeList = attributeList(out);
        List<Element> structuralChildren = elementChildren(attributeList);
        assertEquals("MetadataAttributeUsage", structuralChildren.get(0).getLocalName(), "usage must come first");
        assertEquals("Attribute", structuralChildren.get(1).getLocalName(), "regular Attribute follows");
    }

    @Test
    void noCapturedUsages_returnsInputUnchanged() {
        byte[] input = dsdWithOneAttribute();
        assertArrayEquals(input, sut.inject(input, Map.of()));
    }

    @Test
    void malformedXml_returnsInputUnchanged() {
        byte[] input = "<not-valid-xml".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(input, sut.inject(input, Map.of(KEY, usages(usage("DOI", relationship("none"))))));
    }

    @Test
    @SneakyThrows
    void multipleDsds_matchedByKey() {
        byte[] out = sut.inject(twoDsds(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        Document doc = parse(out);
        NodeList dsds = doc.getElementsByTagNameNS(STRUCTURE_NS, "DataStructure");
        assertEquals(2, dsds.getLength());
        assertEquals(1, usageCount((Element) dsds.item(0)), "matched DSD gets one usage");
        assertEquals(0, usageCount((Element) dsds.item(1)), "unmatched DSD untouched");
    }

    @Test
    @SneakyThrows
    void usageOnlyDsd_createsAttributeListBeforeMeasureList() {
        byte[] out = sut.inject(dsdWithoutAttributeList(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        Document doc = parse(out);
        Element components = (Element) doc.getElementsByTagNameNS(STRUCTURE_NS, "DataStructureComponents").item(0);
        List<Element> order = elementChildren(components);
        List<String> localNames = order.stream().map(Node::getLocalName).toList();
        assertEquals(List.of("DimensionList", "AttributeList", "MeasureList"), localNames, "AttributeList inserted before MeasureList");
        Element attributeList = child(components, "AttributeList");
        assertEquals("AttributeDescriptor", attributeList.getAttribute("id"));
        assertEquals(1, children(attributeList, "MetadataAttributeUsage").size());
    }

    @Test
    @SneakyThrows
    void preservesXmlDeclarationAndEncoding() {
        byte[] out = sut.inject(dsdWithOneAttribute(), Map.of(KEY, usages(usage("DOI", relationship("none")))));

        String xml = new String(out, StandardCharsets.UTF_8);
        assertTrue(xml.startsWith("<?xml"), "XML declaration must survive the DOM round-trip");
        assertTrue(xml.contains("encoding=\"UTF-8\""), "UTF-8 encoding must be declared");
    }

    // --- helpers -------------------------------------------------------------

    private ArrayNode usages(JsonNode... usages) {
        ArrayNode array = mapper.createArrayNode();
        for (JsonNode usage : usages) {
            array.add(usage);
        }
        return array;
    }

    private ObjectNode usage(String reference, ObjectNode relationship) {
        ObjectNode usage = mapper.createObjectNode();
        usage.put("metadataAttributeReference", reference);
        usage.set("attributeRelationship", relationship);
        return usage;
    }

    private ObjectNode relationship(String kind) {
        ObjectNode relationship = mapper.createObjectNode();
        relationship.set(kind, mapper.createObjectNode());
        return relationship;
    }

    private ObjectNode dimensions(String... dimensions) {
        ObjectNode relationship = mapper.createObjectNode();
        ArrayNode array = relationship.putArray("dimensions");
        for (String dimension : dimensions) {
            array.add(dimension);
        }
        return relationship;
    }

    private byte[] dsdWithOneAttribute() {
        return xml("""
                <str:DataStructure agencyID="IMF.STA" id="DSD_QNEA" version="7.0.0">
                  <str:DataStructureComponents>
                    <str:AttributeList id="AttributeDescriptor">
                      <str:Attribute id="OBS_STATUS">
                        <str:AttributeRelationship><str:Observation/></str:AttributeRelationship>
                      </str:Attribute>
                    </str:AttributeList>
                  </str:DataStructureComponents>
                </str:DataStructure>
                """);
    }

    private byte[] dsdWithoutAttributeList() {
        return xml("""
                <str:DataStructure agencyID="IMF.STA" id="DSD_QNEA" version="7.0.0">
                  <str:DataStructureComponents>
                    <str:DimensionList id="DimensionDescriptor"/>
                    <str:MeasureList id="MeasureDescriptor"/>
                  </str:DataStructureComponents>
                </str:DataStructure>
                """);
    }

    private byte[] twoDsds() {
        return xml("""
                <str:DataStructure agencyID="IMF.STA" id="DSD_QNEA" version="7.0.0">
                  <str:DataStructureComponents>
                    <str:AttributeList id="AttributeDescriptor">
                      <str:Attribute id="OBS_STATUS"/>
                    </str:AttributeList>
                  </str:DataStructureComponents>
                </str:DataStructure>
                <str:DataStructure agencyID="IMF.STA" id="DSD_OTHER" version="1.0.0">
                  <str:DataStructureComponents>
                    <str:AttributeList id="AttributeDescriptor">
                      <str:Attribute id="OBS_STATUS"/>
                    </str:AttributeList>
                  </str:DataStructureComponents>
                </str:DataStructure>
                """);
    }

    private byte[] xml(String dataStructures) {
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mes:Structure xmlns:mes="http://www.sdmx.org/resources/sdmxml/schemas/v3_0/message" xmlns:str="%s" xmlns:com="http://www.sdmx.org/resources/sdmxml/schemas/v3_0/common">
                  <mes:Structures>
                    <str:DataStructures>
                %s
                    </str:DataStructures>
                  </mes:Structures>
                </mes:Structure>
                """.formatted(STRUCTURE_NS, dataStructures);
        return document.getBytes(StandardCharsets.UTF_8);
    }

    @SneakyThrows
    private Document parse(byte[] xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    @SneakyThrows
    private Element onlyUsage(byte[] xml) {
        NodeList usages = parse(xml).getElementsByTagNameNS(STRUCTURE_NS, "MetadataAttributeUsage");
        assertEquals(1, usages.getLength(), "exactly one usage expected");
        return (Element) usages.item(0);
    }

    @SneakyThrows
    private Element attributeList(byte[] xml) {
        return (Element) parse(xml).getElementsByTagNameNS(STRUCTURE_NS, "AttributeList").item(0);
    }

    private int usageCount(Element dsd) {
        return dsd.getElementsByTagNameNS(STRUCTURE_NS, "MetadataAttributeUsage").getLength();
    }

    private Element child(Element parent, String localName) {
        List<Element> matches = children(parent, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String childText(Element parent, String localName) {
        Element child = child(parent, localName);
        return child == null ? null : child.getTextContent();
    }

    private List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Element child : elementChildren(parent)) {
            if (localName.equals(child.getLocalName())) {
                result.add(child);
            }
        }
        return result;
    }

    private List<Element> elementChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }
}
