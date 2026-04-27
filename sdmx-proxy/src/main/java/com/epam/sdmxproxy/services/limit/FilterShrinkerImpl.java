package com.epam.sdmxproxy.services.limit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class FilterShrinkerImpl implements FilterShrinker {

    @Override
    public ShrinkDecision decide(
            AvailabilityProjection projection,
            long targetUpperBound,
            String timeDimensionId
    ) {
        long currentSeriesCount = projection.effectiveSeriesCount();
        if (currentSeriesCount <= targetUpperBound || currentSeriesCount == 0L) {
            return ShrinkDecision.none();
        }

        Candidate candidate = pickLargestShrinkableDim(projection, timeDimensionId);
        if (candidate == null) {
            return ShrinkDecision.none();
        }

        int keepCount = computeProportionalKeepCount(
                candidate.size(), targetUpperBound, currentSeriesCount);
        if (keepCount >= candidate.size()) {
            return ShrinkDecision.none();
        }

        List<String> retained = List.copyOf(candidate.values().subList(0, keepCount));
        return new ShrinkDecision(candidate.dimensionId(), retained);
    }

    /**
     * Picks the non-time dimension with the largest value set (ties broken by registry
     * insertion order). Dimensions already narrowed to <=1 value are ineligible. Returns
     * {@code null} when no dimension can be further narrowed.
     */
    private static Candidate pickLargestShrinkableDim(
            AvailabilityProjection projection,
            String timeDimensionId
    ) {
        String chosenDimension = null;
        int chosenSize = -1;
        for (Map.Entry<String, List<String>> entry : projection.valuesByDimensionId().entrySet()) {
            String dimensionId = entry.getKey();
            if (Objects.equals(dimensionId, timeDimensionId)) {
                continue;
            }
            int size = entry.getValue().size();
            if (size <= 1) {
                continue;
            }
            if (size > chosenSize) {
                chosenDimension = dimensionId;
                chosenSize = size;
            }
        }
        if (chosenDimension == null) {
            return null;
        }
        return new Candidate(
                chosenDimension,
                chosenSize,
                projection.valuesByDimensionId().get(chosenDimension));
    }

    /**
     * Proportional step: keep roughly {@code target/current} fraction of the chosen dim's
     * values. {@code ceil} (not {@code floor}) avoids undershoot in a single iteration --
     * see design 014 "Why ceil for k". Clamps to at least 1 and handles {@code Long.MAX_VALUE}
     * overflow from pathological projections.
     */
    private static int computeProportionalKeepCount(
            int candidateSize,
            long targetUpperBound,
            long currentSeriesCount
    ) {
        if (currentSeriesCount == Long.MAX_VALUE) {
            return 1;
        }
        long numerator;
        try {
            numerator = Math.multiplyExact(targetUpperBound, (long) candidateSize);
        } catch (ArithmeticException overflow) {
            numerator = Long.MAX_VALUE;
        }
        long keep = ceilDiv(numerator, currentSeriesCount);
        return (int) Math.max(1L, keep);
    }

    private static long ceilDiv(long numerator, long denominator) {
        if (denominator == 0L) {
            return 0L;
        }
        long quotient = numerator / denominator;
        return (numerator % denominator != 0L) ? quotient + 1L : quotient;
    }

    private record Candidate(String dimensionId, int size, List<String> values) {
    }
}
