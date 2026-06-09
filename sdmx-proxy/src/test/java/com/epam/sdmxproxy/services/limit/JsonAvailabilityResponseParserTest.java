package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonAvailabilityResponseParserTest {

    private final JsonAvailabilityResponseParser parser = new JsonAvailabilityResponseParser();

    @Test
    void parse_imfSampleResponse_extractsDimensionValues() {
        try (InputStream in = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/fixture/availability/imf_weo_availability_response.json")) {
            AvailabilityProjection projection = parser.parse(in, SdmxFormat.JSON_STRUCTURE_2_0_0);

            assertThat(projection.valuesByDimensionId()).isNotEmpty();
            assertThat(projection.valuesByDimensionId().get("COUNTRY"))
                    .as("COUNTRY dim must be populated and include expected codes in registry order")
                    .contains("ABW", "AFG", "ALB");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void parse_bisWsEerResponse_usesKeyValuesAndSeriesCountAnnotation() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/limit/bis_ws_eer_availability.json")) {
            assertThat(in).isNotNull();
            AvailabilityProjection projection = parser.parse(in, SdmxFormat.JSON_STRUCTURE_2_0_0);

            assertThat(projection.seriesCount())
                    .as("series_count annotation must be extracted")
                    .isEqualTo(271L);
            assertThat(projection.effectiveSeriesCount()).isEqualTo(271L);

            assertThat(projection.valuesByDimensionId())
                    .as("BIS uses keyValues, not components -- parser must handle both shapes")
                    .containsKeys("REF_AREA", "FREQ", "EER_TYPE", "EER_BASKET");
            assertThat(projection.valuesByDimensionId().get("REF_AREA")).hasSize(64);
            assertThat(projection.valuesByDimensionId().get("FREQ")).containsExactly("D", "M");
            assertThat(projection.valuesByDimensionId().get("EER_TYPE")).containsExactly("N", "R");
            assertThat(projection.valuesByDimensionId().get("EER_BASKET")).containsExactly("B", "N");
        }
    }

    @Test
    void parse_seriesCountAnnotation_integerTitle() {
        String body = "{\"data\":{\"dataConstraints\":[{\"annotations\":["
                + "{\"id\":\"series_count\",\"title\":\"42\",\"type\":\"sdmx_metrics\"}"
                + "],\"cubeRegions\":[]}]}}";
        AvailabilityProjection projection = parser.parse(toStream(body), SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.seriesCount()).isEqualTo(42L);
    }

    @Test
    void parse_missingSeriesCountAnnotation_nullField() {
        String body = "{\"data\":{\"dataConstraints\":[{\"cubeRegions\":[{\"components\":"
                + "[{\"id\":\"FREQ\",\"values\":[{\"value\":\"A\"}]}]}]}]}}";
        AvailabilityProjection projection = parser.parse(toStream(body), SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.seriesCount()).isNull();
        assertThat(projection.effectiveSeriesCount()).isEqualTo(1L);
    }

    @Test
    void parse_emptyAvailability_returnsEmptyProjection() {
        InputStream in = toStream("{\"data\":{\"dataConstraints\":[]}}");
        AvailabilityProjection projection = parser.parse(in, SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.valuesByDimensionId()).isEmpty();
    }

    @Test
    void parse_singleDimensionSingleValue() {
        String body = "{\"data\":{\"dataConstraints\":[{\"cubeRegions\":[{\"components\":"
                + "[{\"id\":\"FREQ\",\"values\":[{\"value\":\"A\"}]}]}]}]}}";
        AvailabilityProjection projection = parser.parse(toStream(body), SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.valuesByDimensionId()).containsEntry("FREQ", List.of("A"));
    }

    @Test
    void parse_preservesRegistryOrderAndDeduplicates() {
        String body = "{\"data\":{\"dataConstraints\":[{\"cubeRegions\":[{\"components\":["
                + "{\"id\":\"FREQ\",\"values\":[{\"value\":\"A\"},{\"value\":\"D\"},{\"value\":\"A\"}]}"
                + "]}]}]}}";
        AvailabilityProjection projection = parser.parse(toStream(body), SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.valuesByDimensionId()).containsEntry("FREQ", List.of("A", "D"));
    }

    @Test
    void parse_mergesAcrossMultipleCubeRegions() {
        String body = "{\"data\":{\"dataConstraints\":[{\"cubeRegions\":["
                + "{\"components\":[{\"id\":\"FREQ\",\"values\":[{\"value\":\"A\"}]}]},"
                + "{\"components\":[{\"id\":\"FREQ\",\"values\":[{\"value\":\"M\"}]}]}"
                + "]}]}}";
        AvailabilityProjection projection = parser.parse(toStream(body), SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(projection.valuesByDimensionId()).containsEntry("FREQ", List.of("A", "M"));
    }

    @Test
    void supports_onlyJsonStructure200() {
        assertThat(parser.supports(SdmxFormat.JSON_STRUCTURE_2_0_0)).isTrue();
        assertThat(parser.supports(SdmxFormat.JSON_DATA_1_0_0)).isFalse();
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
