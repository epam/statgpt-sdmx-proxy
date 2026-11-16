package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fills missing representations in SDMX-ML 2.1 structure messages -- see design 043, {@code R1}.
 * <p>
 * Statistics Canada (and, per the reporters, the Australian Bureau of Statistics) publish DSDs
 * whose primary measure has no {@code LocalRepresentation}, whose time dimension and some
 * attributes carry an empty {@code <str:TextFormat/>}, and whose concepts have no
 * {@code CoreRepresentation}. sdmx-core reads both as "no representation", and the JSON 2.0
 * output then carries {@code "coreRepresentation": {}} for every concept.
 * <p>
 * Rules, applied only when the document holds both a DSD and a concept scheme, so the answer for
 * one artefact does not depend on how it was requested:
 * <ul>
 *   <li>An empty {@code TextFormat} on a component gets {@code textType="String"}, or
 *       {@code ObservationalTimePeriod} on the time dimension.</li>
 *   <li>A non-time component without {@code LocalRepresentation} is left alone when its concept
 *       has a {@code CoreRepresentation} or is not in the document (it inherits, as the 2.1 schema
 *       allows), and gets {@code String} otherwise. A time dimension without one gets
 *       {@code ObservationalTimePeriod}.</li>
 *   <li>A concept without {@code CoreRepresentation} gets the effective representation of the
 *       first component that references it, or {@code String} when no component does.</li>
 * </ul>
 * Every rule tests for absence first, so the fixture is idempotent; a document that needs no
 * change is returned byte for byte. Concepts are matched on agency, scheme ID, and concept ID; the
 * scheme version is not compared.
 */
@Slf4j
@Component
public class FillMissingRepresentationsXmlFixture implements StructureFixture {

    private static final String STR_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure";

    private static final String STRING = "String";

    private static final String OBSERVATIONAL_TIME_PERIOD = "ObservationalTimePeriod";

    private static final Set<String> COMPONENTS = Set.of("Dimension", "MeasureDimension", "TimeDimension", "Attribute", "PrimaryMeasure");

    private static final Set<String> COMPONENT_LISTS = Set.of("DimensionList", "AttributeList", "MeasureList");

    private final XMLInputFactory inputFactory;

    private final XMLOutputFactory outputFactory;

    private final XMLEventFactory eventFactory;

    public FillMissingRepresentationsXmlFixture() {
        this.inputFactory = XMLInputFactory.newInstance();
        this.inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        this.inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        this.outputFactory = XMLOutputFactory.newInstance();
        this.eventFactory = XMLEventFactory.newInstance();
    }

    @Override
    public StructureFixtureType getType() {
        return StructureFixtureType.FILL_MISSING_REPRESENTATIONS;
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.XML_STRUCTURE_2_1);
    }

    @Override
    @SneakyThrows
    public InputStream apply(InputStream input, Map<String, String> config) {
        byte[] original = input.readAllBytes();
        Collected collected = collect(original);
        if (!collected.hasDsd || collected.conceptsInDocument.isEmpty()) {
            return new ByteArrayInputStream(original);
        }
        Plan plan = plan(collected);
        if (plan.isEmpty()) {
            return new ByteArrayInputStream(original);
        }
        return new ByteArrayInputStream(rewrite(original, plan));
    }

    // ---- Pass 1: collect -------------------------------------------------------------------

    @SneakyThrows
    private Collected collect(byte[] document) {
        Collected collected = new Collected();
        XMLEventReader reader = inputFactory.createXMLEventReader(new ByteArrayInputStream(document));
        Deque<String> stack = new ArrayDeque<>();
        String[] scheme = null;
        String currentConcept = null;
        ComponentInfo component = null;
        List<XMLEvent> localChildren = null;
        int localDepth = 0;
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (localChildren != null) {
                    if (event.isEndElement() && localDepth == 0) {
                        component.localChildren = localChildren;
                        localChildren = null;
                    } else {
                        if (event.isStartElement()) {
                            localDepth++;
                        } else if (event.isEndElement()) {
                            localDepth--;
                        }
                        if (!isWhitespace(event)) {
                            localChildren.add(event);
                        }
                        continue;
                    }
                }
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    String name = start.getName().getLocalPart();
                    String parent = stack.peek();
                    boolean structure = STR_NS.equals(start.getName().getNamespaceURI());
                    if (structure && "DataStructure".equals(name)) {
                        collected.hasDsd = true;
                    } else if (structure && "ConceptScheme".equals(name)) {
                        scheme = new String[]{attr(start, "agencyID"), attr(start, "id")};
                    } else if (structure && "Concept".equals(name) && "ConceptScheme".equals(parent) && scheme != null) {
                        currentConcept = conceptKey(scheme[0], scheme[1], attr(start, "id"));
                        collected.conceptsInDocument.add(currentConcept);
                    } else if (structure && "CoreRepresentation".equals(name) && "Concept".equals(parent) && currentConcept != null) {
                        collected.conceptsWithCore.add(currentConcept);
                    } else if (structure && COMPONENTS.contains(name) && COMPONENT_LISTS.contains(parent)) {
                        component = new ComponentInfo("TimeDimension".equals(name));
                        collected.components.add(component);
                    } else if ("Ref".equals(name) && "ConceptIdentity".equals(parent) && component != null) {
                        component.conceptKey = conceptKey(attr(start, "agencyID"), attr(start, "maintainableParentID"), attr(start, "id"));
                    } else if (structure && "LocalRepresentation".equals(name) && component != null && isComponent(parent)) {
                        component.hasLocal = true;
                        localChildren = new ArrayList<>();
                        localDepth = 0;
                    }
                    stack.push(name);
                } else if (event.isEndElement()) {
                    String name = stack.pop();
                    if ("Concept".equals(name) && "ConceptScheme".equals(stack.peek())) {
                        currentConcept = null;
                    } else if ("ConceptScheme".equals(name)) {
                        scheme = null;
                    } else if (COMPONENTS.contains(name) && COMPONENT_LISTS.contains(stack.peek())) {
                        component = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return collected;
    }

    // ---- Plan ------------------------------------------------------------------------------

    private Plan plan(Collected collected) {
        Plan plan = new Plan();
        plan.componentsInOrder = collected.components;
        Map<String, String> signatures = new HashMap<>();
        for (ComponentInfo component : collected.components) {
            String defaultType = component.time ? OBSERVATIONAL_TIME_PERIOD : STRING;
            List<XMLEvent> effective;
            if (component.hasLocal) {
                effective = withFilledTextFormat(component.localChildren, defaultType);
                if (hasEmptyTextFormat(component.localChildren)) {
                    plan.filledTextFormats.add(component);
                }
            } else if (component.time || (collected.conceptsInDocument.contains(component.conceptKey) && !collected.conceptsWithCore.contains(component.conceptKey))) {
                effective = List.of(textFormatStart(defaultType), textFormatEnd());
                plan.insertedLocal.put(component, effective);
            } else {
                effective = null;
            }
            String key = component.conceptKey;
            if (effective == null || key == null || collected.conceptsWithCore.contains(key) || !collected.conceptsInDocument.contains(key)) {
                continue;
            }
            String signature = signature(effective);
            if (!plan.conceptRepresentations.containsKey(key)) {
                plan.conceptRepresentations.put(key, effective);
                signatures.put(key, signature);
            } else if (!signatures.get(key).equals(signature)) {
                log.warn("Components referencing concept {} have different representations; copying the first one onto the concept", key);
            }
        }
        for (String concept : collected.conceptsInDocument) {
            if (!collected.conceptsWithCore.contains(concept) && !plan.conceptRepresentations.containsKey(concept)) {
                plan.conceptRepresentations.put(concept, List.of(textFormatStart(STRING), textFormatEnd()));
            }
        }
        return plan;
    }

    // ---- Pass 2: rewrite -------------------------------------------------------------------

    @SneakyThrows
    private byte[] rewrite(byte[] document, Plan plan) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(document.length + 8192);
        XMLEventReader reader = inputFactory.createXMLEventReader(new ByteArrayInputStream(document));
        XMLEventWriter writer = outputFactory.createXMLEventWriter(out, "UTF-8");
        Deque<String> stack = new ArrayDeque<>();
        String[] scheme = null;
        String pendingConcept = null;
        String prefix = "str";
        int componentIndex = -1;
        ComponentInfo component = null;
        List<ComponentInfo> components = plan.componentsInOrder;
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    String name = start.getName().getLocalPart();
                    String parent = stack.peek();
                    boolean structure = STR_NS.equals(start.getName().getNamespaceURI());
                    if (structure) {
                        prefix = start.getName().getPrefix();
                    }
                    if (structure && "ConceptScheme".equals(name)) {
                        scheme = new String[]{attr(start, "agencyID"), attr(start, "id")};
                    } else if (structure && "Concept".equals(name) && "ConceptScheme".equals(parent) && scheme != null) {
                        String key = conceptKey(scheme[0], scheme[1], attr(start, "id"));
                        pendingConcept = plan.conceptRepresentations.containsKey(key) ? key : null;
                    } else if (structure && "ISOConceptReference".equals(name) && "Concept".equals(parent) && pendingConcept != null) {
                        writeCoreRepresentation(writer, prefix, plan.conceptRepresentations.get(pendingConcept));
                        pendingConcept = null;
                    } else if (structure && COMPONENTS.contains(name) && COMPONENT_LISTS.contains(parent)) {
                        componentIndex++;
                        component = components.get(componentIndex);
                    } else if (structure && "TextFormat".equals(name) && "LocalRepresentation".equals(parent) && component != null && !start.getAttributes().hasNext() && plan.filledTextFormats.contains(component)) {
                        event = textFormatStart(prefix, component.time ? OBSERVATIONAL_TIME_PERIOD : STRING);
                    }
                    stack.push(name);
                    writer.add(event);
                } else if (event.isEndElement()) {
                    String name = stack.pop();
                    if ("Concept".equals(name) && "ConceptScheme".equals(stack.peek()) && pendingConcept != null) {
                        writeCoreRepresentation(writer, prefix, plan.conceptRepresentations.get(pendingConcept));
                        pendingConcept = null;
                    }
                    writer.add(event);
                    if ("ConceptIdentity".equals(name) && component != null && isComponent(stack.peek()) && plan.insertedLocal.containsKey(component)) {
                        writeWrapped(writer, prefix, "LocalRepresentation", plan.insertedLocal.get(component));
                    } else if (COMPONENTS.contains(name) && COMPONENT_LISTS.contains(stack.peek())) {
                        component = null;
                    } else if ("ConceptScheme".equals(name)) {
                        scheme = null;
                    }
                } else {
                    writer.add(event);
                }
            }
            writer.flush();
        } finally {
            writer.close();
            reader.close();
        }
        return out.toByteArray();
    }

    private void writeCoreRepresentation(XMLEventWriter writer, String prefix, List<XMLEvent> representation) {
        writeWrapped(writer, prefix, "CoreRepresentation", representation);
    }

    @SneakyThrows
    private void writeWrapped(XMLEventWriter writer, String prefix, String wrapper, List<XMLEvent> children) {
        writer.add(eventFactory.createStartElement(prefix, STR_NS, wrapper));
        for (XMLEvent child : children) {
            if (child.isStartElement() && STR_NS.equals(child.asStartElement().getName().getNamespaceURI())) {
                StartElement start = child.asStartElement();
                writer.add(eventFactory.createStartElement(new QName(STR_NS, start.getName().getLocalPart(), prefix), start.getAttributes(), null));
            } else if (child.isEndElement() && STR_NS.equals(child.asEndElement().getName().getNamespaceURI())) {
                writer.add(eventFactory.createEndElement(new QName(STR_NS, child.asEndElement().getName().getLocalPart(), prefix), null));
            } else {
                writer.add(child);
            }
        }
        writer.add(eventFactory.createEndElement(prefix, STR_NS, wrapper));
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private List<XMLEvent> withFilledTextFormat(List<XMLEvent> children, String textType) {
        List<XMLEvent> result = new ArrayList<>(children.size());
        for (XMLEvent child : children) {
            if (isEmptyTextFormat(child)) {
                result.add(textFormatStart(textType));
            } else {
                result.add(child);
            }
        }
        return result;
    }

    private boolean hasEmptyTextFormat(List<XMLEvent> children) {
        return children.stream().anyMatch(this::isEmptyTextFormat);
    }

    private boolean isEmptyTextFormat(XMLEvent event) {
        return event.isStartElement() && "TextFormat".equals(event.asStartElement().getName().getLocalPart()) && STR_NS.equals(event.asStartElement().getName().getNamespaceURI()) && !event.asStartElement().getAttributes().hasNext();
    }

    private StartElement textFormatStart(String textType) {
        return textFormatStart("str", textType);
    }

    private StartElement textFormatStart(String prefix, String textType) {
        return eventFactory.createStartElement(new QName(STR_NS, "TextFormat", prefix), List.of(eventFactory.createAttribute("textType", textType)).iterator(), null);
    }

    private XMLEvent textFormatEnd() {
        return eventFactory.createEndElement(new QName(STR_NS, "TextFormat", "str"), null);
    }

    /** Element names and sorted attributes, to compare two representations. */
    private String signature(List<XMLEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (XMLEvent event : events) {
            if (event.isStartElement()) {
                StartElement start = event.asStartElement();
                Map<String, String> attributes = new TreeMap<>();
                Iterator<Attribute> it = start.getAttributes();
                while (it.hasNext()) {
                    Attribute attribute = it.next();
                    attributes.put(attribute.getName().getLocalPart(), attribute.getValue());
                }
                sb.append('<').append(start.getName().getLocalPart()).append(attributes);
            } else if (event.isEndElement()) {
                sb.append("</").append(event.asEndElement().getName().getLocalPart());
            }
        }
        return sb.toString();
    }

    private boolean isComponent(String localName) {
        return localName != null && COMPONENTS.contains(localName);
    }

    private boolean isWhitespace(XMLEvent event) {
        return event.isCharacters() && event.asCharacters().isWhiteSpace();
    }

    private static String conceptKey(String agency, String scheme, String concept) {
        return agency + ":" + scheme + ":" + concept;
    }

    private static String attr(StartElement start, String localName) {
        Attribute attribute = start.getAttributeByName(new QName(localName));
        return attribute != null ? attribute.getValue() : null;
    }

    private static final class ComponentInfo {
        private final boolean time;
        private String conceptKey;
        private boolean hasLocal;
        private List<XMLEvent> localChildren = List.of();

        private ComponentInfo(boolean time) {
            this.time = time;
        }
    }

    private static final class Collected {
        private boolean hasDsd;
        private final Set<String> conceptsInDocument = new HashSet<>();
        private final Set<String> conceptsWithCore = new HashSet<>();
        private final List<ComponentInfo> components = new ArrayList<>();
    }

    private static final class Plan {
        private List<ComponentInfo> componentsInOrder = List.of();
        private final Set<ComponentInfo> filledTextFormats = new HashSet<>();
        private final Map<ComponentInfo, List<XMLEvent>> insertedLocal = new HashMap<>();
        private final Map<String, List<XMLEvent>> conceptRepresentations = new HashMap<>();

        private boolean isEmpty() {
            return filledTextFormats.isEmpty() && insertedLocal.isEmpty() && conceptRepresentations.isEmpty();
        }
    }
}
