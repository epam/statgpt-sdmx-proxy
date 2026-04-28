package com.epam.sdmxproxy.services.limit;

import org.springframework.stereotype.Component;

/**
 * Pure-function math helper for the limit-emulation bisect algorithm. Stateless; both
 * methods are referentially transparent.
 */
@Component
public class BisectCalculator {

    /**
     * Proportional warm-start: predict the {@code k} on the chosen dimension that lands the
     * series count in the feasible band, assuming uniform cube density. Computed as
     * {@code ceil(target * dimSize / m)} clamped to {@code [1, dimSize - 1]}. The {@code
     * ceil} guarantees the predicted upper bound stays at or above {@code target} (i.e.
     * never undershoots) when the cube actually is uniform; cube non-uniformity is handled
     * by the bisect that follows.
     *
     * @param dimSize size of the chosen dimension's value list (must be {@code >= 2}; one-
     *                value dims are excluded by the dim-selector, never reach this method)
     * @param m       current series count from the last probe
     * @param target  upper edge of the feasible band {@code floor(N * tolerance)}
     * @return number of values to keep on the chosen dim, in {@code [1, dimSize - 1]}
     */
    public int proportionalWarmStart(int dimSize, long m, long target) {
        if (dimSize <= 1) {
            return 1;
        }
        if (m <= 0L) {
            return 1;
        }
        long numerator;
        try {
            numerator = Math.multiplyExact(target, (long) dimSize);
        } catch (ArithmeticException overflow) {
            numerator = Long.MAX_VALUE;
        }
        long k = ceilDiv(numerator, m);
        if (k < 1L) {
            k = 1L;
        }
        if (k > (long) dimSize - 1L) {
            k = (long) dimSize - 1L;
        }
        return (int) k;
    }

    /**
     * Proportional re-step: predict the next {@code k} based on the most recent probe
     * outcome, treating the cube as locally linear. Equivalent to running the warm-start
     * formula again with the just-probed {@code (k, SC)} pair. Avoids the midpoint
     * over-correction that wastes probes when the target lies asymmetrically inside
     * {@code [kLow, kHigh]} -- e.g. a 4% overshoot on the upper bound triggers a 50% cut
     * with plain midpoint, then several probes to climb back.
     * <p>
     * Result is clamped to {@code (kLow, kHigh)} (strictly between the known boundaries),
     * which keeps the invariant that bisect never re-probes a known overshoot or known
     * undershoot. Caller must ensure {@code kHigh - kLow >= 2}; otherwise the bisect has
     * converged and the dim should be locked.
     */
    public int proportionalReStep(int kCurrent, long scCurrent, long target, int kLow, int kHigh) {
        if (scCurrent <= 0L) {
            return kHigh - 1;
        }
        long numerator;
        try {
            numerator = Math.multiplyExact(target, (long) kCurrent);
        } catch (ArithmeticException overflow) {
            numerator = Long.MAX_VALUE;
        }
        long k = ceilDiv(numerator, scCurrent);
        if (k <= (long) kLow) {
            k = (long) kLow + 1L;
        }
        if (k >= (long) kHigh) {
            k = (long) kHigh - 1L;
        }
        return (int) k;
    }

    private static long ceilDiv(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0L;
        }
        long quotient = numerator / denominator;
        return (numerator % denominator != 0L) ? quotient + 1L : quotient;
    }
}
