package com.epam.sdmxproxy.services.fixture.structure;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Injects native SDMX-ML 3.0 {@code <str:MetadataAttributeUsage>} elements into converted XML 3.0
 * structure output, keyed by {@code agencyID|id|version}.
 * <p>
 * sdmx-core's bean model has no slot for a DSD's {@code metadataAttributeUsages}, so they are dropped
 * on the JSON 2.0 -> SdmxBeans -> XML 3.0 round-trip. JSON 2.0 output compensates via
 * {@link MetadataAttributeUsagePreserver} (capture/reinject) and XML 2.1 via
 * {@link MetadataAttributeUsageFolder} (fold into DataAttributes); the XML 3.0 path has neither --
 * this injector closes that gap. It reuses {@link MetadataAttributeUsagePreserver#capture(byte[])}
 * for the raw-JSON usage map and emits the usages as native {@code MetadataAttributeUsage} elements
 * (the SDMX-ML 3.0 representation IMF serves natively).
 * <p>
 * XML 3.0 output path only. Best-effort: on any parse/transform failure the input bytes are returned
 * unchanged (usages then simply absent, the pre-feature behaviour) -- no exception escapes.
 */
@Slf4j
@Service
public class MetadataAttributeUsageXmlInjector {

    private static final String STRUCTURE_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v3_0/structure";
    private static final String DATA_STRUCTURE = "DataStructure";
    private static final String DATA_STRUCTURE_COMPONENTS = "DataStructureComponents";
    private static final String ATTRIBUTE_LIST = "AttributeList";
    private static final String MEASURE_LIST = "MeasureList";
    private static final String ATTRIBUTE = "Attribute";
    private static final String METADATA_ATTRIBUTE_USAGE = "MetadataAttributeUsage";
    private static final String METADATA_ATTRIBUTE_REFERENCE = "MetadataAttributeReference";
    private static final String ATTRIBUTE_RELATIONSHIP = "AttributeRelationship";
    private static final String DATAFLOW = "Dataflow";
    private static final String OBSERVATION = "Observation";
    private static final String DIMENSION = "Dimension";
    private static final String GROUP = "Group";

    private static final String AGENCY_ID = "agencyID";
    private static final String ID = "id";
    private static final String VERSION = "version";

    private static final String JSON_REFERENCE = "metadataAttributeReference";
    private static final String JSON_RELATIONSHIP = "attributeRelationship";
    private static final String JSON_DIMENSIONS = "dimensions";
    private static final String JSON_OBSERVATION = "observation";
    private static final String JSON_GROUP = "group";

    private static final String URN_PREFIX = "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataAttribute=";
    private static final String ATTRIBUTE_DESCRIPTOR = "AttributeDescriptor";

    /**
     * Returns {@code convertedXml} with each DSD's captured {@code metadataAttributeUsages} emitted as
     * native {@code <str:MetadataAttributeUsage>} elements in its {@code AttributeList}. Best-effort:
     * unchanged input on empty captures or any DOM failure.
     *
     * @param convertedXml the XML 3.0 bytes produced by the writer
     * @param usagesByKey   captured raw-JSON usages arrays keyed by {@code agencyID|id|version}
     */
    public byte[] inject(byte[] convertedXml, Map<String, JsonNode> usagesByKey) {
        if (usagesByKey == null || usagesByKey.isEmpty() || convertedXml == null || convertedXml.length == 0) {
            return convertedXml;
        }
        try {
            Document doc = parse(convertedXml);
            NodeList dsds = doc.getElementsByTagNameNS(STRUCTURE_NS, DATA_STRUCTURE);
            int injected = 0;
            for (int i = 0; i < dsds.getLength(); i++) {
                Element dsd = (Element) dsds.item(i);
                String key = keyOf(dsd);
                if (key == null) {
                    continue;
                }
                JsonNode usages = usagesByKey.get(key);
                if (usages == null || !usages.isArray() || usages.isEmpty()) {
                    continue;
                }
                injected += injectIntoDsd(doc, dsd, usages);
            }
            if (injected == 0) {
                return convertedXml;
            }
            log.debug("Injected MetadataAttributeUsage element(s) into {} DSD(s) on XML 3.0 output", injected);
            return serialize(doc);
        } catch (Exception e) {
            log.warn("Failed to inject metadataAttributeUsages into converted XML 3.0; returning unchanged output", e);
            return convertedXml;
        }
    }

    private int injectIntoDsd(Document doc, Element dsd, JsonNode usages) {
        Element components = firstChildElement(dsd, DATA_STRUCTURE_COMPONENTS);
        if (components == null) {
            return 0;
        }
        String prefix = components.getPrefix();
        Element attributeList = firstChildElement(components, ATTRIBUTE_LIST);
        if (attributeList == null) {
            attributeList = createAttributeList(doc, prefix, components);
        }
        Element insertBefore = firstChildElement(attributeList, ATTRIBUTE);
        String dsdAgency = dsd.getAttribute(AGENCY_ID);
        String dsdId = dsd.getAttribute(ID);
        String dsdVersion = dsd.getAttribute(VERSION);
        int count = 0;
        for (JsonNode usage : usages) {
            String reference = usage.path(JSON_REFERENCE).asText("");
            if (reference.isEmpty()) {
                continue;
            }
            Element usageElement = buildUsage(doc, prefix, usage, reference, dsdAgency, dsdId, dsdVersion);
            attributeList.insertBefore(usageElement, insertBefore);
            count++;
        }
        return count;
    }

    /**
     * Creates an {@code <AttributeList id="AttributeDescriptor">} and inserts it into
     * {@code DataStructureComponents} in schema order (after {@code DimensionList}/{@code Group}, before
     * {@code MeasureList}). Exercised by usage-only DSDs, where the v3 writer omits the empty list.
     */
    private Element createAttributeList(Document doc, String prefix, Element components) {
        Element attributeList = createElement(doc, prefix, ATTRIBUTE_LIST);
        attributeList.setAttribute(ID, ATTRIBUTE_DESCRIPTOR);
        Element measureList = firstChildElement(components, MEASURE_LIST);
        components.insertBefore(attributeList, measureList);
        return attributeList;
    }

    private Element buildUsage(Document doc, String prefix, JsonNode usage, String reference, String dsdAgency, String dsdId, String dsdVersion) {
        Element usageElement = createElement(doc, prefix, METADATA_ATTRIBUTE_USAGE);
        usageElement.setAttribute("urn", URN_PREFIX + dsdAgency + ":" + dsdId + "(" + dsdVersion + ")." + reference);

        Element referenceElement = createElement(doc, prefix, METADATA_ATTRIBUTE_REFERENCE);
        referenceElement.setTextContent(reference);
        usageElement.appendChild(referenceElement);

        usageElement.appendChild(buildRelationship(doc, prefix, usage.path(JSON_RELATIONSHIP)));
        return usageElement;
    }

    /**
     * Builds the (XSD-required) {@code AttributeRelationship} per the design's encoding map. A usage with
     * no recognised relationship (including {@code none} and a missing {@code attributeRelationship})
     * falls back to {@code <Dataflow/>} (dataset/DSD-level; deliberate divergence from IMF, which omits it).
     */
    private Element buildRelationship(Document doc, String prefix, JsonNode relationship) {
        Element relationshipElement = createElement(doc, prefix, ATTRIBUTE_RELATIONSHIP);
        JsonNode dimensions = relationship.path(JSON_DIMENSIONS);
        if (dimensions.isArray() && !dimensions.isEmpty()) {
            for (JsonNode dimension : dimensions) {
                Element dimensionElement = createElement(doc, prefix, DIMENSION);
                dimensionElement.setTextContent(dimension.asText());
                relationshipElement.appendChild(dimensionElement);
            }
        } else if (relationship.has(JSON_OBSERVATION)) {
            relationshipElement.appendChild(createElement(doc, prefix, OBSERVATION));
        } else if (relationship.has(JSON_GROUP) && relationship.path(JSON_GROUP).isTextual()) {
            Element groupElement = createElement(doc, prefix, GROUP);
            groupElement.setTextContent(relationship.path(JSON_GROUP).asText());
            relationshipElement.appendChild(groupElement);
        } else {
            relationshipElement.appendChild(createElement(doc, prefix, DATAFLOW));
        }
        return relationshipElement;
    }

    private Element createElement(Document doc, String prefix, String localName) {
        String qualified = prefix == null || prefix.isEmpty() ? localName : prefix + ":" + localName;
        return (Element) doc.createElementNS(STRUCTURE_NS, qualified);
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && STRUCTURE_NS.equals(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String keyOf(Element dsd) {
        String agency = dsd.getAttribute(AGENCY_ID);
        String id = dsd.getAttribute(ID);
        String version = dsd.getAttribute(VERSION);
        if (agency.isEmpty() || id.isEmpty() || version.isEmpty()) {
            return null;
        }
        return agency + "|" + id + "|" + version;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static byte[] serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}
