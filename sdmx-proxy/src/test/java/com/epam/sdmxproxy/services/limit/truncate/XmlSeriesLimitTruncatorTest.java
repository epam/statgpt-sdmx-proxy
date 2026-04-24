package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class XmlSeriesLimitTruncatorTest {

    private XmlSeriesLimitTruncator truncator;

    @BeforeEach
    void setUp() {
        truncator = new XmlSeriesLimitTruncator(new StreamingFixtureIO());
    }

    @Test
    void truncate_genericDataFormat_emitsFirstNSeries() throws Exception {
        String xml = genericData(5);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(xml), 3, null)) {
            out = in.readAllBytes();
        }

        assertThat(countSeriesElements(out)).isEqualTo(3);
    }

    @Test
    void truncate_structureSpecificFormat_emitsFirstNSeries() throws Exception {
        String xml = structureSpecificData(5);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(xml), 2, null)) {
            out = in.readAllBytes();
        }

        assertThat(countSeriesElements(out)).isEqualTo(2);
    }

    @Test
    void truncate_undershoot_emitsAll() throws Exception {
        String xml = genericData(3);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(xml), 10, null)) {
            out = in.readAllBytes();
        }

        assertThat(countSeriesElements(out)).isEqualTo(3);
    }

    @Test
    void truncate_preservesEnvelope() throws Exception {
        String xml = genericData(5);

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(xml), 2, null)) {
            out = in.readAllBytes();
        }

        String output = new String(out, StandardCharsets.UTF_8);
        assertThat(output).contains("GenericData").contains("DataSet").contains("Header");
    }

    @Test
    void emptyStream_isWellFormedXml() throws Exception {
        try (InputStream in = truncator.emptyStream(null)) {
            byte[] bytes = in.readAllBytes();
            XMLStreamReader reader = XMLInputFactory.newFactory()
                    .createXMLStreamReader(new ByteArrayInputStream(bytes));
            boolean rootSeen = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT && "GenericData".equals(reader.getLocalName())) {
                    rootSeen = true;
                }
            }
            assertThat(rootSeen).as("empty stream must parse and contain GenericData root").isTrue();
        }
    }

    private static int countSeriesElements(byte[] xmlBytes) throws Exception {
        XMLStreamReader reader = XMLInputFactory.newFactory()
                .createXMLStreamReader(new ByteArrayInputStream(xmlBytes));
        int count = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamReader.START_ELEMENT && "Series".equals(reader.getLocalName())) {
                count++;
            }
        }
        return count;
    }

    private static String genericData(int seriesCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<message:GenericData ");
        sb.append("xmlns:message=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message\" ");
        sb.append("xmlns:generic=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic\">");
        sb.append("<message:Header><message:ID>test</message:ID></message:Header>");
        sb.append("<message:DataSet>");
        for (int i = 0; i < seriesCount; i++) {
            sb.append("<generic:Series>");
            sb.append("<generic:SeriesKey><generic:Value id=\"FREQ\" value=\"A\"/></generic:SeriesKey>");
            sb.append("<generic:Obs><generic:ObsDimension value=\"2020\"/><generic:ObsValue value=\"").append(i).append(".0\"/></generic:Obs>");
            sb.append("</generic:Series>");
        }
        sb.append("</message:DataSet>");
        sb.append("</message:GenericData>");
        return sb.toString();
    }

    private static String structureSpecificData(int seriesCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<message:StructureSpecificData ");
        sb.append("xmlns:message=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message\" ");
        sb.append("xmlns:ns=\"urn:sdmx:test\">");
        sb.append("<message:Header><message:ID>test</message:ID></message:Header>");
        sb.append("<message:DataSet>");
        for (int i = 0; i < seriesCount; i++) {
            sb.append("<ns:Series FREQ=\"A\" REF_AREA=\"C").append(i).append("\">");
            sb.append("<ns:Obs TIME_PERIOD=\"2020\" OBS_VALUE=\"").append(i).append(".0\"/>");
            sb.append("</ns:Series>");
        }
        sb.append("</message:DataSet>");
        sb.append("</message:StructureSpecificData>");
        return sb.toString();
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
