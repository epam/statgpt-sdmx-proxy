package com.epam.sdmxproxy.services.limit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Cacheable form of a limit-emulation shrunk query: just the bits the bisect
 * decided. Carries the path key the algorithm settled on (typically {@code "*"} for
 * SDMX 3.0 with c[]-routing, a positional key for 2.1) and the per-dim filter map.
 * Enough to rebuild the {@code TranslatedDataQuery} on a fresh request without
 * re-running the availability probes.
 */
public record CachedShrinkResult(
        @JsonProperty("key") String key,
        @JsonProperty("filters") Map<String, List<String>> filters
) {

    @JsonCreator
    public CachedShrinkResult {
    }
}
