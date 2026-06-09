package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.XMLEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Streaming truncator for SDMX-ML data responses -- covers both
 * {@link SdmxFormat#XML_GENERIC_DATA_2_1 generic data} and
 * {@link SdmxFormat#XML_STRUCTURE_SPECIFIC_DATA_2_1 structure-specific data}. In both flavors
 * a series is a {@code <Series>} element (local-name match, namespace-agnostic) nested
 * inside {@code <DataSet>}. The transform copies every event through verbatim; when the
 * running series counter reaches {@code n} it starts skipping subsequent {@code <Series>}
 * subtrees (StAX {@code XMLEventReader} / {@code XMLEventWriter}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XmlSeriesLimitTruncator implements SeriesLimitTruncator {

    private static final String SERIES_LOCAL_NAME = "Series";

    private static final byte[] EMPTY_PAYLOAD = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<message:GenericData"
                    + " xmlns:message=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message\">"
                    + "<message:DataSet/>"
                    + "</message:GenericData>"
    ).getBytes(StandardCharsets.UTF_8);

    private final StreamingFixtureIO streamingFixtureIO;

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(
                SdmxFormat.XML_GENERIC_DATA_2_1,
                SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1
        );
    }

    @Override
    public InputStream truncate(InputStream rawData, int n, SdmxBeans sdmxBeans) {
        XMLInputFactory inFactory = XMLInputFactory.newFactory();
        XMLOutputFactory outFactory = XMLOutputFactory.newFactory();
        return streamingFixtureIO.runOnVirtualThread(
                rawData,
                "limit-truncate-xml",
                (in, out) -> {
                    XMLEventReader reader = inFactory.createXMLEventReader(in, "UTF-8");
                    XMLEventWriter writer = outFactory.createXMLEventWriter(out, "UTF-8");
                    try {
                        transform(reader, writer, n);
                    } finally {
                        writer.close();
                        reader.close();
                    }
                }
        );
    }

    @Override
    public InputStream emptyStream(SdmxBeans sdmxBeans) {
        return new ByteArrayInputStream(EMPTY_PAYLOAD);
    }

    @SneakyThrows
    private static void transform(XMLEventReader reader, XMLEventWriter writer, int n) {
        int seriesSeen = 0;
        int skipDepth = 0;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (skipDepth > 0) {
                if (event.isStartElement()) {
                    skipDepth++;
                } else if (event.isEndElement()) {
                    skipDepth--;
                }
                continue;
            }
            if (event.isStartElement()
                    && SERIES_LOCAL_NAME.equals(event.asStartElement().getName().getLocalPart())) {
                if (seriesSeen >= n) {
                    skipDepth = 1;
                    continue;
                }
                seriesSeen++;
            }
            writer.add(event);
        }
    }
}
