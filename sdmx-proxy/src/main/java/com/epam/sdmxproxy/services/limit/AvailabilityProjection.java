package com.epam.sdmxproxy.services.limit;

import java.util.List;
import java.util.Map;

/**
 * Parsed projection of an availability response: per-dimension value sets plus the
 * optional {@code series_count} annotation reported by the registry.
 *
 * @param valuesByDimensionId per-dim list of values with data, in registry order
 * @param seriesCount         series count reported by the {@code series_count} annotation
 *                            on the data constraint; {@code null} when absent
 */
public record AvailabilityProjection(
        Map<String, List<String>> valuesByDimensionId,
        Long seriesCount
) {

    public AvailabilityProjection(Map<String, List<String>> valuesByDimensionId) {
        this(valuesByDimensionId, null);
    }

    /**
     * Series count used by the shrink loop: the registry-reported {@link #seriesCount}
     * when present (cheap and accurate), otherwise the combinatorial upper bound
     * {@code prod |A_d|} as a conservative fallback.
     */
    public long effectiveSeriesCount() {
        if (seriesCount != null && seriesCount >= 0L) {
            return seriesCount;
        }
        return combinatorialUpperBound();
    }

    /**
     * @return product of per-dim sizes; 0 if any dim is empty; {@link Long#MAX_VALUE} on
     *         overflow (pathological DSD, see design 014 edge cases). Never throws.
     */
    public long combinatorialUpperBound() {
        long product = 1L;
        for (List<String> values : valuesByDimensionId.values()) {
            if (values.isEmpty()) {
                return 0L;
            }
            try {
                product = Math.multiplyExact(product, (long) values.size());
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return product;
    }
}
