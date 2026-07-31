package com.epam.sdmxproxy.services.translator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sdmx21QueryNormalizerImplTest {

    private final Sdmx21QueryNormalizer normalizer = new Sdmx21QueryNormalizerImpl();

    @Test
    void shouldMapStarKeyToAll() {
        assertThat(normalizer.toKey("*")).isEqualTo("all");
    }

    @Test
    void shouldMapNullAndEmptyKeyToAll() {
        assertThat(normalizer.toKey(null)).isEqualTo("all");
        assertThat(normalizer.toKey("")).isEqualTo("all");
    }

    @Test
    void shouldEmptyPerPositionWildcards() {
        assertThat(normalizer.toKey("USA.A.*.*.*")).isEqualTo("USA.A...");
    }

    @Test
    void shouldCollapseAllWildcardPositionalKeyToAll() {
        assertThat(normalizer.toKey("*.*.*")).isEqualTo("all");
        assertThat(normalizer.toKey("..")).isEqualTo("all");
    }

    @Test
    void shouldPreserveOrValues() {
        assertThat(normalizer.toKey("A.B+C.*")).isEqualTo("A.B+C.");
    }

    @Test
    void shouldLeave21KeyUnchanged() {
        assertThat(normalizer.toKey("USA.A...")).isEqualTo("USA.A...");
        assertThat(normalizer.toKey("all")).isEqualTo("all");
    }

    @Test
    void shouldMapStarComponentIdToAll() {
        assertThat(normalizer.toComponentId("*")).isEqualTo("all");
        assertThat(normalizer.toComponentId(null)).isEqualTo("all");
    }

    @Test
    void shouldLeaveSingleComponentIdUnchanged() {
        assertThat(normalizer.toComponentId("REF_AREA")).isEqualTo("REF_AREA");
    }

    @Test
    void shouldLeaveMultiComponentIdUnchanged() {
        // SDMX 3.0 allows multiple componentIds, so collapsing a client's list to `all` would
        // silently widen the request. Only the proxy's own generated values are rewritten.
        assertThat(normalizer.toComponentId("REF_AREA,FREQ")).isEqualTo("REF_AREA,FREQ");
    }

    @Test
    void shouldMapNoneReferencesToNull() {
        assertThat(normalizer.toReferences("none")).isNull();
        assertThat(normalizer.toReferences("all")).isEqualTo("all");
        assertThat(normalizer.toReferences(null)).isNull();
    }

    @Test
    void shouldMapStarPathSlotToAll() {
        assertThat(normalizer.toSdmx21PathSlot("*")).isEqualTo("all");
        assertThat(normalizer.toSdmx21PathSlot(null)).isEqualTo("all");
        assertThat(normalizer.toSdmx21PathSlot("1.0")).isEqualTo("1.0");
    }

    @Test
    void shouldBeIdempotent() {
        String key = normalizer.toKey(normalizer.toKey("USA.A.*.*.*"));
        assertThat(key).isEqualTo("USA.A...");
        assertThat(normalizer.toComponentId(normalizer.toComponentId("*"))).isEqualTo("all");
        assertThat(normalizer.toReferences(normalizer.toReferences("none"))).isNull();
        assertThat(normalizer.toSdmx21PathSlot(normalizer.toSdmx21PathSlot("*"))).isEqualTo("all");
    }
}
