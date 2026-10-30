package com.epam.sdmxproxy.services.fixture.data;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepairEmptyDataSetXmlDataFixtureTest {

    private final RepairEmptyDataSetXmlDataFixture fixture = new RepairEmptyDataSetXmlDataFixture();

    @Test
    void shouldDropStraySeriesEndTagStructureSpecific() {
        byte[] repaired = apply(read("statcan/empty_ss.xml"));

        String text = new String(repaired, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("</Series>");
        assertThat(seriesCount(repaired)).isZero();
    }

    @Test
    void shouldDropStraySeriesEndTagGeneric() {
        byte[] repaired = apply(read("statcan/empty_generic.xml"));

        assertThat(new String(repaired, StandardCharsets.UTF_8)).doesNotContain("</generic:Series>");
        assertThat(seriesCount(repaired)).isZero();
    }

    @Test
    void shouldPassThroughNonEmptyData() {
        byte[] original = read("statcan/DF_17100005_1.1.1_ss.xml");

        assertThat(apply(original)).isEqualTo(original);
    }

    @Test
    void shouldNotMatchHeaderDataSetElements() {
        String body = "<message:StructureSpecificData xmlns:message=\"m\"><message:Header><message:DataSetID>DS1</message:DataSetID><message:DataSetAction>Information</message:DataSetAction></message:Header><message:DataSet ss:dataScope=\"DataStructure\" xmlns:ss=\"s\"></Series> </message:DataSet></message:StructureSpecificData>";

        String repaired = new String(apply(body.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

        assertThat(repaired).contains("<message:DataSetID>DS1</message:DataSetID>");
        assertThat(repaired).contains("<message:DataSet ss:dataScope=\"DataStructure\" xmlns:ss=\"s\"> </message:DataSet>");
    }

    @Test
    void shouldPassThroughPastBufferCap() {
        String padding = "x".repeat(RepairEmptyDataSetXmlDataFixture.MAX_BUFFER + 10);
        byte[] body = ("<message:Header><message:ID>" + padding + "</message:ID></message:Header><message:DataSet></Series></message:DataSet>").getBytes(StandardCharsets.UTF_8);

        assertThat(apply(body)).isEqualTo(body);
    }

    @Test
    void shouldHandleSelfClosingDataSet() {
        byte[] body = "<message:DataSet structureRef=\"X\"/></Series>".getBytes(StandardCharsets.UTF_8);

        assertThat(apply(body)).isEqualTo(body);
    }

    @SneakyThrows
    private byte[] apply(byte[] input) {
        try (InputStream repaired = fixture.apply(new ByteArrayInputStream(input), null, Map.of())) {
            return repaired.readAllBytes();
        }
    }

    @SneakyThrows
    private int seriesCount(byte[] document) {
        XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(new ByteArrayInputStream(document));
        int count = 0;
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamReader.START_ELEMENT && "Series".equals(reader.getLocalName())) {
                count++;
            }
        }
        return count;
    }

    @SneakyThrows
    private byte[] read(String resource) {
        return getClass().getResourceAsStream(resource).readAllBytes();
    }
}
