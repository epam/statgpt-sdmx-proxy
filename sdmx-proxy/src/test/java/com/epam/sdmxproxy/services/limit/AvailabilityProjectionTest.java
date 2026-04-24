package com.epam.sdmxproxy.services.limit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityProjectionTest {

    @Test
    void combinatorialUpperBound_denseCube_productOfSizes() {
        AvailabilityProjection projection = projection(
                Map.of("A", List.of("1", "2", "3"),
                        "B", List.of("x", "y"),
                        "C", List.of("p", "q", "r", "s")));

        assertThat(projection.combinatorialUpperBound()).isEqualTo(24L);
    }

    @Test
    void combinatorialUpperBound_emptyDim_shortCircuitsToZero() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("A", List.of("1", "2", "3"));
        m.put("EMPTY", List.of());
        m.put("B", List.of("x"));

        assertThat(new AvailabilityProjection(m).combinatorialUpperBound()).isZero();
    }

    @Test
    void effectiveSeriesCount_returnsAnnotationWhenPresent() {
        AvailabilityProjection projection = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("A", "D"),
                        "COUNTRY", List.of("UK", "PL", "JP"))),
                271L);
        // combinatorial would be 6; series_count annotation wins
        assertThat(projection.effectiveSeriesCount()).isEqualTo(271L);
    }

    @Test
    void effectiveSeriesCount_fallsBackToCombinatorialWhenAnnotationMissing() {
        AvailabilityProjection projection = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("A", "D"),
                        "COUNTRY", List.of("UK", "PL", "JP"))));
        assertThat(projection.seriesCount()).isNull();
        assertThat(projection.effectiveSeriesCount()).isEqualTo(6L);
    }

    @Test
    void combinatorialUpperBound_overflow_returnsLongMaxValue() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        List<String> pair = List.of("a", "b");
        for (int i = 0; i < 70; i++) {
            m.put("D" + i, pair);
        }
        assertThat(new AvailabilityProjection(m).combinatorialUpperBound()).isEqualTo(Long.MAX_VALUE);
    }

    private static AvailabilityProjection projection(Map<String, List<String>> values) {
        return new AvailabilityProjection(new LinkedHashMap<>(values));
    }
}
