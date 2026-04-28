package com.epam.sdmxproxy.services.limit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BisectCalculatorTest {

    private final BisectCalculator calculator = new BisectCalculator();

    @Test
    void proportionalWarmStart_uniformCube_lands_on_target_proportion() {
        // dim=100, m=4000, target=1200 -> ceil(1200*100/4000) = ceil(30) = 30
        assertThat(calculator.proportionalWarmStart(100, 4000L, 1200L)).isEqualTo(30);
    }

    @Test
    void proportionalWarmStart_ceilingRounds_up_avoids_undershoot_in_uniform_case() {
        // dim=10, m=10, target=3 -> ceil(3*10/10) = 3 (exact); slightly different: target=4 -> ceil(40/10) = 4
        assertThat(calculator.proportionalWarmStart(10, 10L, 4L)).isEqualTo(4);
        // Non-divisible: dim=7, m=20, target=3 -> ceil(3*7/20) = ceil(21/20) = 2
        assertThat(calculator.proportionalWarmStart(7, 20L, 3L)).isEqualTo(2);
        // Edge non-divisible: dim=9, m=10, target=2 -> ceil(2*9/10) = ceil(18/10) = 2
        assertThat(calculator.proportionalWarmStart(9, 10L, 2L)).isEqualTo(2);
    }

    @Test
    void proportionalWarmStart_clampsToOne_whenTargetIsZero() {
        assertThat(calculator.proportionalWarmStart(50, 1000L, 0L)).isEqualTo(1);
    }

    @Test
    void proportionalWarmStart_clampsToDimSizeMinusOne_whenProportionExceedsDim() {
        // target > m: proportional formula yields >= dimSize, clamp to dimSize-1
        assertThat(calculator.proportionalWarmStart(10, 50L, 100L)).isEqualTo(9);
        // m == target case: ceil(m*dim/m) = dim, clamp to dim-1
        assertThat(calculator.proportionalWarmStart(10, 100L, 100L)).isEqualTo(9);
    }

    @Test
    void proportionalWarmStart_returnsOne_whenDimSizeIsOne() {
        assertThat(calculator.proportionalWarmStart(1, 100L, 50L)).isEqualTo(1);
    }

    @Test
    void proportionalWarmStart_handlesOverflow_inMultiplication() {
        // target * dimSize overflows long; numerator saturates to Long.MAX_VALUE; with small m,
        // k blows up and is clamped to dimSize - 1.
        assertThat(calculator.proportionalWarmStart(100, 1L, Long.MAX_VALUE)).isEqualTo(99);
    }

    @Test
    void proportionalWarmStart_returnsOne_whenMIsZero() {
        assertThat(calculator.proportionalWarmStart(50, 0L, 100L)).isEqualTo(1);
    }

    @Test
    void proportionalReStep_smallOvershoot_takesSmallStepDown() {
        // Production scenario: kCurrent=34, SC=100, target=96, kLow=0, kHigh=34
        // Re-step: k = ceil(96 * 34 / 100) = ceil(32.64) = 33. Single-step adjustment.
        assertThat(calculator.proportionalReStep(34, 100L, 96L, 0, 34)).isEqualTo(33);
    }

    @Test
    void proportionalReStep_largeOvershoot_takesLargeStepDown() {
        // kCurrent=34, SC=10000, target=96 -> ceil(96 * 34 / 10000) = 1, clamp to kLow+1
        assertThat(calculator.proportionalReStep(34, 10000L, 96L, 0, 34)).isEqualTo(1);
    }

    @Test
    void proportionalReStep_undershoot_takesStepUp() {
        // kCurrent=10, SC=20, target=96, kLow=10, kHigh=64
        // Re-step: k = ceil(96 * 10 / 20) = 48. In (10, 64). OK.
        assertThat(calculator.proportionalReStep(10, 20L, 96L, 10, 64)).isEqualTo(48);
    }

    @Test
    void proportionalReStep_clampsAboveKLow() {
        // SC much larger than target, formula yields k <= kLow -> clamp to kLow + 1
        assertThat(calculator.proportionalReStep(34, 100000L, 96L, 5, 34)).isEqualTo(6);
    }

    @Test
    void proportionalReStep_clampsBelowKHigh() {
        // Tiny SC, formula yields k >= kHigh -> clamp to kHigh - 1
        assertThat(calculator.proportionalReStep(10, 1L, 96L, 5, 50)).isEqualTo(49);
    }

    @Test
    void proportionalReStep_handlesZeroSC() {
        // Edge: SC=0 means undershoot extreme; default to kHigh - 1 (most aggressive expansion)
        assertThat(calculator.proportionalReStep(10, 0L, 96L, 5, 50)).isEqualTo(49);
    }

    @Test
    void proportionalReStep_handlesOverflowInMultiplication() {
        // target * kCurrent overflows -> numerator saturates to Long.MAX_VALUE
        // -> k blows up -> clamp to kHigh - 1
        assertThat(calculator.proportionalReStep(1_000_000, 1L, Long.MAX_VALUE, 0, 100))
                .isEqualTo(99);
    }

    @Test
    void proportionalReStep_minimumSpan_clampsToInBetween() {
        // kHigh - kLow = 2 -> only one valid k (kLow + 1 = kHigh - 1). Result must be that.
        assertThat(calculator.proportionalReStep(10, 100L, 96L, 9, 11)).isEqualTo(10);
    }
}
