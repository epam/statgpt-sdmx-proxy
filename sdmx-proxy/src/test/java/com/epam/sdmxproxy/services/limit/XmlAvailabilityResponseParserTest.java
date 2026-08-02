package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityProbeException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlAvailabilityResponseParserTest {

    private final XmlAvailabilityResponseParser parser = new XmlAvailabilityResponseParser();

    @Test
    void shouldSupportOnlyXmlStructure21() {
        assertThat(parser.supports(SdmxFormat.XML_STRUCTURE_2_1)).isTrue();
        assertThat(parser.supports(SdmxFormat.JSON_STRUCTURE_2_0_0)).isFalse();
    }

    @Test
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> parser.parse(stream("<x/>"), SdmxFormat.JSON_STRUCTURE_2_0_0))
                .isInstanceOf(AvailabilityProbeException.class)
                .hasMessageContaining("Unsupported availability return format");
    }

    @Test
    void shouldParseKeyValuesInRegistryOrder() {
        AvailabilityProjection projection = parser.parse(stream(oecdStyleResponse()), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId()).containsOnlyKeys("REF_AREA", "FREQ");
        assertThat(projection.valuesByDimensionId().get("REF_AREA")).containsExactly("AUS", "AUT", "BEL");
        assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("A", "Q");
    }

    @Test
    void shouldSkipTimeDimensionReportedAsTimeRange() {
        // TIME_PERIOD carries a TimeRange, not Value children. Recording it as an empty dimension
        // would drive combinatorialUpperBound() to zero, i.e. "the cube is empty".
        AvailabilityProjection projection = parser.parse(stream(oecdStyleResponse()), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId()).doesNotContainKey("TIME_PERIOD");
        assertThat(projection.combinatorialUpperBound()).isEqualTo(6L);
    }

    @Test
    void shouldIgnoreObsCountAnnotation() {
        // obs_count counts observations, not series; using it would over-estimate and over-shrink.
        AvailabilityProjection projection = parser.parse(stream(oecdStyleResponse()), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.seriesCount()).isNull();
        assertThat(projection.effectiveSeriesCount()).isEqualTo(6L);
    }

    @Test
    void shouldReadSeriesCountAnnotationWhenPresent() {
        String xml = oecdStyleResponse().replace("id=\"obs_count\"", "id=\"series_count\"");

        AvailabilityProjection projection = parser.parse(stream(xml), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.seriesCount()).isEqualTo(4338043L);
        assertThat(projection.effectiveSeriesCount()).isEqualTo(4338043L);
    }

    @Test
    void shouldDeduplicateRepeatedValuesAcrossCubeRegions() {
        String xml = """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                                   xmlns:structure="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure"
                                   xmlns:common="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common">
                  <message:Structures><structure:Constraints>
                    <structure:ContentConstraint id="CC1">
                      <structure:CubeRegion include="true">
                        <common:KeyValue id="FREQ"><common:Value>A</common:Value></common:KeyValue>
                      </structure:CubeRegion>
                    </structure:ContentConstraint>
                    <structure:ContentConstraint id="CC2">
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
    void shouldSubtractValuesListedInExclusionCubeRegion() {
        // include="false" marks values that are NOT available. Treating them as available would make
        // the bisect narrow onto values the cube does not contain -- silently, with no error.
        AvailabilityProjection projection = parser.parse(stream(withExclusionRegion()), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId().get("REF_AREA")).containsExactly("AUS", "BEL");
        assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("A", "Q");
    }

    @Test
    void shouldSubtractExclusionRegardlessOfRegionOrder() {
        // Inclusions and exclusions are reconciled after the whole document is read, so an exclusion
        // region appearing before the inclusion region must give the same answer.
        AvailabilityProjection includeFirst = parser.parse(stream(withExclusionRegion()), SdmxFormat.XML_STRUCTURE_2_1);
        AvailabilityProjection excludeFirst = parser.parse(stream(exclusionRegionFirst()), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(excludeFirst.valuesByDimensionId()).isEqualTo(includeFirst.valuesByDimensionId());
        assertThat(excludeFirst.valuesByDimensionId().get("REF_AREA")).containsExactly("AUS", "BEL");
    }

    @Test
    void shouldTreatMissingIncludeAttributeAsInclusion() {
        String xml = """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                                   xmlns:structure="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure"
                                   xmlns:common="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common">
                  <message:Structures><structure:Constraints>
                    <structure:ContentConstraint id="CC">
                      <structure:CubeRegion>
                        <common:KeyValue id="FREQ"><common:Value>A</common:Value></common:KeyValue>
                      </structure:CubeRegion>
                    </structure:ContentConstraint>
                  </structure:Constraints></message:Structures>
                </message:Structure>
                """;

        AvailabilityProjection projection = parser.parse(stream(xml), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("A");
    }

    @Test
    void shouldReturnEmptyProjectionForResponseWithoutCubeRegion() {
        String xml = """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message">
                  <message:Structures/>
                </message:Structure>
                """;

        AvailabilityProjection projection = parser.parse(stream(xml), SdmxFormat.XML_STRUCTURE_2_1);

        assertThat(projection.valuesByDimensionId()).isEmpty();
    }

    @Test
    void shouldFailOnMalformedXml() {
        assertThatThrownBy(() -> parser.parse(stream("<message:Structure><unclosed>"), SdmxFormat.XML_STRUCTURE_2_1))
                .isInstanceOf(AvailabilityProbeException.class)
                .hasMessageContaining("Failed to parse availability response");
    }

    private static String oecdStyleResponse() {
        return """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                                   xmlns:structure="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure"
                                   xmlns:common="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common">
                  <message:Structures><structure:Constraints>
                    <structure:ContentConstraint id="CC" agencyID="SDMX" version="1.0" type="Actual">
                      <common:Annotations>
                        <common:Annotation id="obs_count">
                          <common:AnnotationTitle>4338043</common:AnnotationTitle>
                          <common:AnnotationType>sdmx_metrics</common:AnnotationType>
                        </common:Annotation>
                      </common:Annotations>
                      <common:Name xml:lang="en">Autogenerated content constraint</common:Name>
                      <structure:CubeRegion include="true">
                        <common:KeyValue id="REF_AREA">
                          <common:Value>AUS</common:Value>
                          <common:Value>AUT</common:Value>
                          <common:Value>BEL</common:Value>
                        </common:KeyValue>
                        <common:KeyValue id="FREQ">
                          <common:Value>A</common:Value>
                          <common:Value>Q</common:Value>
                        </common:KeyValue>
                        <common:KeyValue id="TIME_PERIOD">
                          <common:TimeRange>
                            <common:StartPeriod isInclusive="true">1953-01-01T00:00:00</common:StartPeriod>
                            <common:EndPeriod isInclusive="true">2026-06-30T00:00:00</common:EndPeriod>
                          </common:TimeRange>
                        </common:KeyValue>
                      </structure:CubeRegion>
                    </structure:ContentConstraint>
                  </structure:Constraints></message:Structures>
                </message:Structure>
                """;
    }

    private static String withExclusionRegion() {
        return constraintWithRegions("""
                      <structure:CubeRegion include="true">
                        <common:KeyValue id="REF_AREA">
                          <common:Value>AUS</common:Value>
                          <common:Value>AUT</common:Value>
                          <common:Value>BEL</common:Value>
                        </common:KeyValue>
                        <common:KeyValue id="FREQ">
                          <common:Value>A</common:Value>
                          <common:Value>Q</common:Value>
                        </common:KeyValue>
                      </structure:CubeRegion>
                      <structure:CubeRegion include="false">
                        <common:KeyValue id="REF_AREA">
                          <common:Value>AUT</common:Value>
                        </common:KeyValue>
                      </structure:CubeRegion>
                """);
    }

    private static String exclusionRegionFirst() {
        return constraintWithRegions("""
                      <structure:CubeRegion include="false">
                        <common:KeyValue id="REF_AREA">
                          <common:Value>AUT</common:Value>
                        </common:KeyValue>
                      </structure:CubeRegion>
                      <structure:CubeRegion include="true">
                        <common:KeyValue id="REF_AREA">
                          <common:Value>AUS</common:Value>
                          <common:Value>AUT</common:Value>
                          <common:Value>BEL</common:Value>
                        </common:KeyValue>
                        <common:KeyValue id="FREQ">
                          <common:Value>A</common:Value>
                          <common:Value>Q</common:Value>
                        </common:KeyValue>
                      </structure:CubeRegion>
                """);
    }

    private static String constraintWithRegions(String regions) {
        return """
                <message:Structure xmlns:message="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                                   xmlns:structure="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure"
                                   xmlns:common="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common">
                  <message:Structures><structure:Constraints>
                    <structure:ContentConstraint id="CC">
                %s
                    </structure:ContentConstraint>
                  </structure:Constraints></message:Structures>
                </message:Structure>
                """.formatted(regions);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
