package com.epam.sdmxproxy.services.limit;

import java.util.List;
import java.util.Map;

/**
 * Parse and rebuild SDMX 2.1 / 3.0 positional keys.
 * <p>
 * SDMX key grammar: positions joined with {@code .}, values within a position joined with
 * {@code +} (OR). {@code *} or an empty position denotes wildcard. A single {@code *} or
 * the literal {@code all} denotes all-wildcard.
 * <p>
 * The in-memory representation is an ordered {@code Map<dimensionId, List<value>>} --
 * matching {@link AvailabilityProjection#valuesByDimensionId()} so the bisect
 * orchestration in {@link LimitEmulationServiceImpl} can apply per-dim narrowings directly.
 * An empty list = wildcard for that dim.
 */
public interface KeyParser {

    /**
     * Parses a key into per-dimension value lists. Inputs {@code null}, {@code ""},
     * {@code "*"}, {@code "all"} produce all-wildcard. Positional keys shorter than
     * {@code nonTimeDimensionIds} leave the trailing positions as wildcard.
     */
    Map<String, List<String>> parseKey(String key, List<String> nonTimeDimensionIds);

    /**
     * Builds a positional key string. Empty value list = wildcard for that dim. When
     * every dim is wildcard and {@code mergeAllWildcard} is true, collapses to a single
     * {@code "*"} -- matches {@code DataEndpointConfiguration.mergeAllWildcardKey}
     * semantics applied by {@code QueryTranslatorImpl}.
     */
    String buildKey(
            Map<String, List<String>> perDim,
            List<String> nonTimeDimensionIds,
            boolean mergeAllWildcard
    );
}
