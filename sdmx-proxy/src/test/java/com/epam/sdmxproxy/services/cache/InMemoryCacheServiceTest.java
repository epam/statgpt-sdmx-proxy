package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design 041 / P8. Cache keys identify a registry by name only, so a configuration change has to
 * drop everything or the next request is served a response fetched under the previous endpoint.
 */
class InMemoryCacheServiceTest {

    private InMemoryCacheService sut;

    @BeforeEach
    void setUp() {
        sut = new InMemoryCacheService(new CacheProperties());
    }

    @Test
    void shouldInvalidateAllThreeCaches() {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        sut.putRawStructures("structure:dataflow:ESTAT", payload);
        sut.putReadyResponse("response:structure:dataflow:ESTAT", payload);
        sut.putLimitEmulationShrinkFilters("limit_emu:ESTAT", payload);

        sut.invalidateAll();

        assertTrue(sut.getRawStructures("structure:dataflow:ESTAT").isEmpty(), "raw structures must be dropped");
        assertTrue(sut.getReadyResponse("response:structure:dataflow:ESTAT").isEmpty(), "ready responses must be dropped");
        assertTrue(sut.getLimitEmulationShrinkFilters("limit_emu:ESTAT").isEmpty(), "limit emulation entries must be dropped");
    }
}
