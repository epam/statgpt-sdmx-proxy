package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityProbeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegatingAvailabilityResponseParserTest {

    private DelegatingAvailabilityResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new DelegatingAvailabilityResponseParser(new JsonAvailabilityResponseParser(), new XmlAvailabilityResponseParser());
    }

    @Test
    void shouldSupportBothRegistryAvailabilityFormats() {
        assertThat(parser.supports(SdmxFormat.JSON_STRUCTURE_2_0_0)).isTrue();
        assertThat(parser.supports(SdmxFormat.XML_STRUCTURE_2_1)).isTrue();
    }

    @Test
    void shouldNotSupportFormatsNoParserHandles() {
        assertThat(parser.supports(SdmxFormat.XML_STRUCTURE_3_0_0)).isFalse();
        assertThat(parser.supports(SdmxFormat.CSV_DATA_2_0_0)).isFalse();
    }

    @Test
    void shouldRouteJsonToTheJsonParser() {
        String json = """
                {"data":{"dataConstraints":[{"cubeRegions":[{"components":[
                  {"id":"FREQ","values":[{"value":"A"},{"value":"M"}]}
                ]}]}]}}
                """;

        AvailabilityProjection projection = parser.parse(stream(json), SdmxFormat.JSON_STRUCTURE_2_0_0);

        assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("A", "M");
    }

    @Test
    void shouldRouteXmlToTheXmlParser() {
        String xml = """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                                   xmlns:structure="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure"
                                   xmlns:common="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common">
                  <message:Structures><structure:Constraints>
                    <structure:ContentConstraint id="CC">
                      <structure:CubeRegion include="true">
                        <common:KeyValue id="FREQ"><common:Value>A</common:Value><common:Value>M</common:Value></common:KeyValue>
                      </structure:CubeRegion>
                    </structure:ContentConstraint>
                  </structure:Constraints></message:Structures>
                </message:Structure>
                """;

        AvailabilityProjection projection = parser.parse(stream(xml), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("A", "M");
    }

    @Test
    void shouldFailWithActionableMessageWhenNoParserMatches() {
        assertThatThrownBy(() -> parser.parse(stream("{}"), SdmxFormat.XML_STRUCTURE_3_0_0))
                .isInstanceOf(AvailabilityProbeException.class)
                .hasMessageContaining("No availability parser for return format")
                .hasMessageContaining("XML_STRUCTURE_3_0_0");
    }

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
