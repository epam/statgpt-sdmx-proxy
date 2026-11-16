package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;

/**
 * Turns the Statistics Canada WDS {@code getAllCubesListLite} table list into an SDMX-ML 2.1
 * structure message with one {@code Dataflow} stub per table -- see design 043, {@code B2}.
 * <p>
 * StatCan's SDMX service has no dataflow list. Each stub carries the dataflow ID, the English and
 * French titles, and the reference to the table's DSD; the full artefacts come from the table's
 * bundle. The WDS array is about 5 MB, so it is read with a streaming parser.
 */
@Slf4j
@Service
public class StatCanCatalogWriter {

    private static final String MES_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message";

    private static final String STR_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure";

    private static final String COM_NS = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common";

    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private static final String CURRENT_TABLE = "2";

    private final StatCanIdentifiers identifiers;

    private final JsonFactory jsonFactory = new JsonFactory();

    private final XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();

    public StatCanCatalogWriter(StatCanIdentifiers identifiers) {
        this.identifiers = identifiers;
    }

    /**
     * Writes the catalog. A table is kept when {@code includeArchived} is true or its
     * {@code archived} flag is {@code "2"} (current). Entries without a {@code productId} or an
     * English title are skipped.
     */
    @SneakyThrows
    public byte[] write(InputStream cubesListLite, boolean includeArchived) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLStreamWriter writer = outputFactory.createXMLStreamWriter(out, "UTF-8");
        int kept = 0;
        int skipped = 0;
        try (JsonParser parser = jsonFactory.createParser(cubesListLite)) {
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("mes", "Structure", MES_NS);
            writer.writeNamespace("mes", MES_NS);
            writer.writeNamespace("str", STR_NS);
            writer.writeNamespace("com", COM_NS);
            writeHeader(writer);
            writer.writeStartElement("mes", "Structures", MES_NS);
            writer.writeStartElement("str", "Dataflows", STR_NS);

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new UnexpectedStateException("Statistics Canada getAllCubesListLite did not return a JSON array");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                Cube cube = readCube(parser);
                if (cube.productId() == null || cube.titleEn() == null) {
                    log.debug("Skipping Statistics Canada catalog entry without productId or English title: {}", cube.productId());
                    skipped++;
                    continue;
                }
                if (!includeArchived && !CURRENT_TABLE.equals(cube.archived())) {
                    continue;
                }
                writeDataflow(writer, cube);
                kept++;
            }

            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } finally {
            writer.close();
        }
        log.info("Built Statistics Canada dataflow catalog: {} tables kept, {} malformed entries skipped, includeArchived={}", kept, skipped, includeArchived);
        return out.toByteArray();
    }

    @SneakyThrows
    private Cube readCube(JsonParser parser) {
        String productId = null;
        String titleEn = null;
        String titleFr = null;
        String archived = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            switch (field) {
                case "productId" -> productId = value.isScalarValue() && value != JsonToken.VALUE_NULL ? parser.getValueAsString() : null;
                case "cubeTitleEn" -> titleEn = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                case "cubeTitleFr" -> titleFr = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                case "archived" -> archived = value.isScalarValue() && value != JsonToken.VALUE_NULL ? parser.getValueAsString() : null;
                default -> parser.skipChildren();
            }
        }
        return new Cube(productId, titleEn, titleFr, archived);
    }

    @SneakyThrows
    private void writeHeader(XMLStreamWriter writer) {
        writer.writeStartElement("mes", "Header", MES_NS);
        writeText(writer, "mes", "ID", MES_NS, "StatCanCatalog");
        writeText(writer, "mes", "Test", MES_NS, "false");
        writeText(writer, "mes", "Prepared", MES_NS, Instant.now().toString());
        writer.writeEmptyElement("mes", "Sender", MES_NS);
        writer.writeAttribute("id", StatCanIdentifiers.AGENCY);
        writer.writeEndElement();
    }

    @SneakyThrows
    private void writeDataflow(XMLStreamWriter writer, Cube cube) {
        String dataflowId = identifiers.dataflowIdOf(cube.productId());
        writer.writeStartElement("str", "Dataflow", STR_NS);
        writer.writeAttribute("id", dataflowId);
        writer.writeAttribute("urn", "urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=" + StatCanIdentifiers.AGENCY + ":" + dataflowId + "(" + StatCanIdentifiers.VERSION + ")");
        writer.writeAttribute("agencyID", StatCanIdentifiers.AGENCY);
        writer.writeAttribute("version", StatCanIdentifiers.VERSION);
        writeName(writer, "en", cube.titleEn());
        if (cube.titleFr() != null) {
            writeName(writer, "fr", cube.titleFr());
        }
        writer.writeStartElement("str", "Structure", STR_NS);
        writer.writeEmptyElement("Ref");
        writer.writeAttribute("id", identifiers.dsdIdOf(cube.productId()));
        writer.writeAttribute("version", StatCanIdentifiers.VERSION);
        writer.writeAttribute("agencyID", StatCanIdentifiers.AGENCY);
        writer.writeAttribute("package", "datastructure");
        writer.writeAttribute("class", "DataStructure");
        writer.writeEndElement();
        writer.writeEndElement();
    }

    @SneakyThrows
    private void writeName(XMLStreamWriter writer, String lang, String text) {
        writer.writeStartElement("com", "Name", COM_NS);
        writer.writeAttribute("xml", XML_NS, "lang", lang);
        writer.writeCharacters(text);
        writer.writeEndElement();
    }

    @SneakyThrows
    private void writeText(XMLStreamWriter writer, String prefix, String localName, String namespace, String text) {
        writer.writeStartElement(prefix, localName, namespace);
        writer.writeCharacters(text);
        writer.writeEndElement();
    }

    private record Cube(String productId, String titleEn, String titleFr, String archived) {
    }
}
