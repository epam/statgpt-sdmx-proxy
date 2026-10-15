package com.epam.sdmxproxy.services.availability.harvest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension value sets observed in a series-key data probe, plus the exact number of series
 * the probe returned -- see design 040.
 * <p>
 * The point of this shape is that it is bounded by the DSD rather than by the size of the probe.
 * Measured on Eurostat: a 139 610 759-byte probe carrying 2 067 919 series harvests to 327
 * strings. So a harvester streams the response and accumulates only this, never the key set.
 *
 * @param valuesByDimensionId per-dimension observed values, in first-seen order
 * @param seriesCount         number of series rows observed; exact, unlike the {@code series_count}
 *                            annotation the limit-emulation bisect estimates from
 */
public record HarvestedCoverage(
        Map<String, Set<String>> valuesByDimensionId,
        long seriesCount
) {

    public static HarvestedCoverage empty() {
        return new HarvestedCoverage(new LinkedHashMap<>(), 0L);
    }

    /**
     * Unions this coverage with {@code other}, per dimension, and sums the series counts. Used to
     * recombine the chunks of a decomposed probe.
     * <p>
     * Union is exact here, not an approximation: a cube region is a rectangular projection, so
     * for every dimension other than the split dimension the union across chunks equals the
     * unsplit result, and for the split dimension the union of the chunk values that returned
     * rows equals the unsplit result. There is no joint structure for splitting to lose.
     */
    public HarvestedCoverage merge(HarvestedCoverage other) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        valuesByDimensionId.forEach((dim, values) -> merged.put(dim, new LinkedHashSet<>(values)));
        other.valuesByDimensionId().forEach(
                (dim, values) -> merged.computeIfAbsent(dim, unused -> new LinkedHashSet<>()).addAll(values)
        );
        return new HarvestedCoverage(merged, seriesCount + other.seriesCount());
    }
}
