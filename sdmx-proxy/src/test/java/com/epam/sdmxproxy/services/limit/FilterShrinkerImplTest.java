package com.epam.sdmxproxy.services.limit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilterShrinkerImplTest {

    private final FilterShrinkerImpl shrinker = new FilterShrinkerImpl();

    @Test
    void decide_unconstrainedSymmetricCube_picksFirstLargest() {
        AvailabilityProjection projection = projection(
                "FREQ", List.of("A", "D", "M"),
                "CTRY", List.of("UK", "PL", "JP"),
                "IND", List.of("I1", "I2", "I3")
        );

        ShrinkDecision decision = shrinker.decide(projection, 12L, null);

        assertThat(decision.isNone()).isFalse();
        assertThat(decision.getDimensionId()).isEqualTo("FREQ");
        assertThat(decision.getRetainedValues()).hasSize(2);
    }

    @Test
    void decide_picksLargestDimension() {
        AvailabilityProjection projection = projection(
                "SMALL", List.of("a", "b"),
                "BIG", List.of("x", "y", "z", "w", "v")
        );

        ShrinkDecision decision = shrinker.decide(projection, 4L, null);

        assertThat(decision.getDimensionId()).isEqualTo("BIG");
    }

    @Test
    void decide_computesProportionalK() {
        AvailabilityProjection projection = projection(
                "D", List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9") // size 10
        );
        // target=20, |A|=10, M=10 -> k = ceil(20*10/10) = 20 -> >= 10 -> none
        ShrinkDecision highTarget = shrinker.decide(projection, 20L, null);
        assertThat(highTarget.isNone()).isTrue();

        // target=5, M=10 -> since M(10) > target(5), select D; k = ceil(5*10/10) = 5
        ShrinkDecision decision = shrinker.decide(projection, 5L, null);
        assertThat(decision.getDimensionId()).isEqualTo("D");
        assertThat(decision.getRetainedValues()).hasSize(5);
    }

    @Test
    void decide_excludesTimeDimension() {
        AvailabilityProjection projection = projection(
                "TIME_PERIOD", List.of("2020", "2021", "2022", "2023", "2024", "2025"),
                "FREQ", List.of("A", "Q", "M")
        );

        ShrinkDecision decision = shrinker.decide(projection, 5L, "TIME_PERIOD");

        assertThat(decision.getDimensionId()).isEqualTo("FREQ");
    }

    @Test
    void decide_skipsSingleValueDims() {
        AvailabilityProjection projection = projection(
                "ONE", List.of("only"),
                "TWO", List.of("a", "b"),
                "TEN", List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
        );

        ShrinkDecision decision = shrinker.decide(projection, 4L, null);

        assertThat(decision.getDimensionId()).isEqualTo("TEN");
    }

    @Test
    void decide_returnsNoneWhenAllDimsSingleValue() {
        AvailabilityProjection projection = projection(
                "A", List.of("x"),
                "B", List.of("y")
        );

        ShrinkDecision decision = shrinker.decide(projection, 0L, null);

        assertThat(decision.isNone()).isTrue();
    }

    @Test
    void decide_kAlwaysAtLeastOne() {
        AvailabilityProjection projection = projection(
                "D", List.of("a", "b", "c", "d", "e")
        );

        // very small target -> k would be 0, clamp to 1
        ShrinkDecision decision = shrinker.decide(projection, 0L, null);

        // When target is 0 and M>0, we skip (m > target always, but k would be 0 and clamp to 1)
        assertThat(decision.isNone()).isFalse();
        assertThat(decision.getRetainedValues()).hasSize(1);
    }

    @Test
    void decide_returnsNoneWhenTargetAlreadyMet() {
        AvailabilityProjection projection = projection(
                "D", List.of("a", "b", "c")
        );

        ShrinkDecision decision = shrinker.decide(projection, 10L, null);

        assertThat(decision.isNone()).isTrue();
    }

    private static AvailabilityProjection projection(Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> vals = (List<String>) kv[i + 1];
            m.put((String) kv[i], vals);
        }
        return new AvailabilityProjection(m);
    }
}
