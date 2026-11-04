package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the codelist and concept-scheme identifiers of one Statistics Canada bundle unique to its
 * table -- see design 043, {@code B3}.
 * <p>
 * Every bundle names its codelists {@code StatCan:CL_{dimension}(1.0)} and its concept scheme
 * {@code StatCan:CONCEPTS(1.0)}, with different content per table. The renamer rewrites the
 * maintainable IDs, every reference to them, and every URN that embeds them. Dataflow, DSD, and
 * agency IDs are already unique per table and stay as they are.
 */
@Service
public class StatCanBundleRenamer {

    private static final String STRUCTURE_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure";

    private static final Pattern CODELIST_URN = Pattern.compile("(Codelist|Code)=StatCan:(CL_[^(]+)\\(");

    private static final Pattern CONCEPT_SCHEME_URN = Pattern.compile("(ConceptScheme|Concept)=StatCan:(CONCEPTS[^(]*)\\(");

    private final StatCanIdentifiers identifiers;

    private final XMLInputFactory inputFactory;

    private final XMLOutputFactory outputFactory;

    private final XMLEventFactory eventFactory;

    public StatCanBundleRenamer(StatCanIdentifiers identifiers) {
        this.identifiers = identifiers;
        this.inputFactory = XMLInputFactory.newInstance();
        this.inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        this.inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        this.outputFactory = XMLOutputFactory.newInstance();
        this.eventFactory = XMLEventFactory.newInstance();
    }

    /**
     * Renames the bundle of {@code table}. The table comes from the request; the bundle's DSD ID
     * must match it, so a change in StatCan's naming surfaces as an error instead of silent mixing.
     */
    @SneakyThrows
    public byte[] rename(byte[] bundle, String table) {
        String expectedDsdId = identifiers.dsdIdOf(table);
        boolean dsdSeen = false;
        ByteArrayOutputStream out = new ByteArrayOutputStream(bundle.length + 4096);
        XMLEventReader reader = inputFactory.createXMLEventReader(new ByteArrayInputStream(bundle));
        XMLEventWriter writer = outputFactory.createXMLEventWriter(out, "UTF-8");
        try {
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    if (isStructureElement(start, "DataStructure")) {
                        String dsdId = attributeValue(start, "id");
                        if (!expectedDsdId.equals(dsdId)) {
                            throw new UnexpectedStateException("Statistics Canada bundle for table " + table + " holds DSD '" + dsdId + "', expected '" + expectedDsdId + "'");
                        }
                        dsdSeen = true;
                    }
                    writer.add(rewrite(start, table));
                } else {
                    writer.add(event);
                }
            }
            writer.flush();
        } finally {
            writer.close();
            reader.close();
        }
        if (!dsdSeen) {
            throw new UnexpectedStateException("Statistics Canada bundle for table " + table + " holds no DSD");
        }
        return out.toByteArray();
    }

    private StartElement rewrite(StartElement start, String table) {
        String localName = start.getName().getLocalPart();
        String refClass = "Ref".equals(localName) ? attributeValue(start, "class") : null;
        List<Attribute> attributes = new ArrayList<>();
        boolean changed = false;
        Iterator<Attribute> it = start.getAttributes();
        while (it.hasNext()) {
            Attribute attribute = it.next();
            String name = attribute.getName().getLocalPart();
            String value = attribute.getValue();
            String rewritten = value;
            if ("urn".equals(name)) {
                rewritten = rewriteUrn(value, table);
            } else if ("id".equals(name) && isStructureElement(start, "Codelist")) {
                rewritten = identifiers.renamedCodelistId(table, value);
            } else if ("id".equals(name) && isStructureElement(start, "ConceptScheme")) {
                rewritten = identifiers.renamedConceptSchemeId(table, value);
            } else if ("id".equals(name) && "Codelist".equals(refClass)) {
                rewritten = identifiers.renamedCodelistId(table, value);
            } else if ("id".equals(name) && "ConceptScheme".equals(refClass)) {
                rewritten = identifiers.renamedConceptSchemeId(table, value);
            } else if ("maintainableParentID".equals(name) && "Concept".equals(refClass)) {
                rewritten = identifiers.renamedConceptSchemeId(table, value);
            }
            if (!rewritten.equals(value)) {
                changed = true;
                attributes.add(eventFactory.createAttribute(attribute.getName(), rewritten));
            } else {
                attributes.add(attribute);
            }
        }
        if (!changed) {
            return start;
        }
        return eventFactory.createStartElement(start.getName(), attributes.iterator(), start.getNamespaces());
    }

    private String rewriteUrn(String urn, String table) {
        Matcher codelist = CODELIST_URN.matcher(urn);
        if (codelist.find()) {
            return urn.substring(0, codelist.start(2)) + identifiers.renamedCodelistId(table, codelist.group(2)) + urn.substring(codelist.end(2));
        }
        Matcher scheme = CONCEPT_SCHEME_URN.matcher(urn);
        if (scheme.find()) {
            return urn.substring(0, scheme.start(2)) + identifiers.renamedConceptSchemeId(table, scheme.group(2)) + urn.substring(scheme.end(2));
        }
        return urn;
    }

    private boolean isStructureElement(StartElement start, String localName) {
        return localName.equals(start.getName().getLocalPart()) && STRUCTURE_NS.equals(start.getName().getNamespaceURI());
    }

    private String attributeValue(StartElement start, String localName) {
        Iterator<Attribute> it = start.getAttributes();
        while (it.hasNext()) {
            Attribute attribute = it.next();
            if (localName.equals(attribute.getName().getLocalPart())) {
                return attribute.getValue();
            }
        }
        return null;
    }
}
