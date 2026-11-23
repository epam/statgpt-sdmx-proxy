package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.services.cache.config.TtlProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 044: the refresh settings must neither refresh every entry on every run nor let entries
 * expire between two runs.
 */
class PreheatPropertiesTest {

    private final TtlProperties ttl = new TtlProperties();

    @Test
    void defaultsShouldPass() {
        assertThatCode(() -> new PreheatProperties().validate(ttl)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectRefreshWindowReachingRawTtl() {
        PreheatProperties properties = new PreheatProperties();
        properties.setRefreshAhead(Duration.ofHours(18));
        properties.setRefreshSpread(Duration.ofHours(6));

        assertThatThrownBy(() -> properties.validate(ttl)).isInstanceOf(IllegalStateException.class).hasMessageContaining("refreshSpread");
    }

    @Test
    void shouldRejectRefreshAheadNotCoveringIntervalAndJitter() {
        PreheatProperties properties = new PreheatProperties();
        properties.setRefreshAhead(Duration.ofHours(2));

        assertThatThrownBy(() -> properties.validate(ttl)).isInstanceOf(IllegalStateException.class).hasMessageContaining("refreshAhead");
    }

    @Test
    void shouldRejectReadyRefreshAheadUnderInterval() {
        PreheatProperties properties = new PreheatProperties();
        properties.setReadyRefreshAhead(Duration.ofMinutes(30));

        assertThatThrownBy(() -> properties.validate(ttl)).isInstanceOf(IllegalStateException.class).hasMessageContaining("readyRefreshAhead");
    }

    @Test
    void shouldRejectReadyRefreshAheadReachingReadyTtl() {
        PreheatProperties properties = new PreheatProperties();
        properties.setReadyRefreshAhead(Duration.ofHours(6));

        assertThatThrownBy(() -> properties.validate(ttl)).isInstanceOf(IllegalStateException.class).hasMessageContaining("readyRefreshAhead");
    }
}
