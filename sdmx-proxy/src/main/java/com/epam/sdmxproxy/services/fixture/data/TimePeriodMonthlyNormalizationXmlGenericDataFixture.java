package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes monthly {@code TIME_PERIOD} values in SDMX-ML 2.1 Generic data responses.
 * <p>
 * In Generic format, the observation time appears as:
 * <pre>{@code <generic:ObsDimension id="TIME_PERIOD" value="2024-03"/>}</pre>
 * The fixture rewrites the {@code value} attribute to canonical {@code YYYY-Mmm} when the
 * element's {@code id} attribute is {@code TIME_PERIOD} and the current value matches
 * {@code YYYY-MM}.
 * <p>
 * Streaming end-to-end via StAX event reader/writer over a pipe on a virtual thread.
 */
@Slf4j
@Component
public class TimePeriodMonthlyNormalizationXmlGenericDataFixture implements DataFixture {

    private static final Pattern MONTHLY = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final String OBS_DIMENSION = "ObsDimension";
    private static final String ID = "id";
    private static final String VALUE = "value";
    private static final String TIME_PERIOD = "TIME_PERIOD";

    private final XMLInputFactory inputFactory;
    private final XMLOutputFactory outputFactory;
    private final XMLEventFactory eventFactory;
    private final StreamingFixtureIO streamingFixtureIO;

    public TimePeriodMonthlyNormalizationXmlGenericDataFixture(StreamingFixtureIO streamingFixtureIO) {
        this.inputFactory = XMLInputFactory.newInstance();
        this.inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        this.outputFactory = XMLOutputFactory.newInstance();
        this.eventFactory = XMLEventFactory.newInstance();
        this.streamingFixtureIO = streamingFixtureIO;
    }

    @Override
    public DataFixtureType getType() {
        return DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.XML_GENERICDATA_2_1);
    }

    @Override
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        return streamingFixtureIO.runOnVirtualThread(input, "data-fixture-monthly-time-period-xml-generic", (in, out) -> {
            XMLEventReader reader = inputFactory.createXMLEventReader(in);
            XMLEventWriter writer = outputFactory.createXMLEventWriter(out);
            try {
                while (reader.hasNext()) {
                    XMLEvent event = reader.nextEvent();
                    if (event.isStartElement()) {
                        StartElement rewritten = maybeRewrite(event.asStartElement());
                        writer.add(rewritten);
                    } else {
                        writer.add(event);
                    }
                }
                writer.flush();
            } finally {
                writer.close();
                reader.close();
            }
        });
    }

    private StartElement maybeRewrite(StartElement start) {
        if (!OBS_DIMENSION.equals(start.getName().getLocalPart())) {
            return start;
        }
        // SDMX-ML 2.1 Generic omits the `id` attribute on ObsDimension when the observation
        // dimension is the implicit TIME_PERIOD (the common case -- BIS emits it this way).
        // Treat missing id as "assumed TIME_PERIOD"; if present, only rewrite when it matches.
        Attribute idAttr = start.getAttributeByName(new javax.xml.namespace.QName(ID));
        if (idAttr != null && !TIME_PERIOD.equals(idAttr.getValue())) {
            return start;
        }

        List<Attribute> newAttrs = new ArrayList<>();
        Iterator<Attribute> attrs = start.getAttributes();
        boolean rewritten = false;
        while (attrs.hasNext()) {
            Attribute attr = attrs.next();
            if (VALUE.equals(attr.getName().getLocalPart())) {
                String normalized = normalize(attr.getValue());
                if (!normalized.equals(attr.getValue())) {
                    rewritten = true;
                    newAttrs.add(eventFactory.createAttribute(attr.getName(), normalized));
                    continue;
                }
            }
            newAttrs.add(attr);
        }
        if (!rewritten) {
            return start;
        }
        return eventFactory.createStartElement(
                start.getName(),
                newAttrs.iterator(),
                start.getNamespaces()
        );
    }

    private String normalize(String value) {
        Matcher matcher = MONTHLY.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(1) + "-M" + matcher.group(2);
    }
}
