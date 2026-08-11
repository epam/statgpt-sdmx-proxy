package com.epam.sdmxproxy.services.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The routing predicate is pinned first because two callers depend on agreeing about it -- the
 * emulator routes on it and the cache key folds it in -- and a disagreement would be silent.
 */
class AvailabilityQueryCanonicalizerTest {

    private final AvailabilityQueryCanonicalizer canonicalizer = new AvailabilityQueryCanonicalizer();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "*", "all", "ALL", "*.*.*.*", "...", "*..*", ".*.", "*.*"})
    @DisplayName("keys that narrow nothing are all recognized as wildcards")
    void wildcardKeys(String key) {
        assertTrue(canonicalizer.isWildcardKey(key), "expected '" + key + "' to narrow nothing");
        assertEquals(AvailabilityQueryCanonicalizer.WILDCARD, canonicalizer.canonicalKey(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "A.CLV05_MEUR.B1GQ.EL", "A...EL", "*.*.*.EL", "A+M...", "..B1GQ."})
    @DisplayName("a key with any concrete position narrows something")
    void narrowingKeys(String key) {
        assertFalse(canonicalizer.isWildcardKey(key), "expected '" + key + "' to narrow something");
        assertEquals(key, canonicalizer.canonicalKey(key));
    }

    @Test
    @DisplayName("absent, empty and blank-valued filter maps are the same request")
    void emptyFilters() {
        assertTrue(canonicalizer.isEmptyFilters(null));
        assertTrue(canonicalizer.isEmptyFilters(new LinkedMultiValueMap<>()));

        MultiValueMap<String, String> blank = new LinkedMultiValueMap<>();
        blank.put("geo", List.of("", "  "));
        assertTrue(canonicalizer.isEmptyFilters(blank));

        MultiValueMap<String, String> nullValued = new LinkedMultiValueMap<>();
        nullValued.put("geo", null);
        assertTrue(canonicalizer.isEmptyFilters(nullValued));
    }

    @Test
    @DisplayName("a filter with any real value narrows something")
    void nonEmptyFilters() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("geo", "EL");
        assertFalse(canonicalizer.isEmptyFilters(filters));
    }

    @Test
    @DisplayName("the two shapes the consumer sends an unfiltered request in are one request")
    void bothUnfilteredCallSitesAgree() {
        // Dataset load: GET route, no key in the path, no c[] parameters.
        assertTrue(canonicalizer.isUnfiltered(null, null));
        // Indicator index build: POST route, body of {"filters": []}.
        assertTrue(canonicalizer.isUnfiltered(null, new LinkedMultiValueMap<>()));
        // And the forms a translator may hand over for the same request.
        assertTrue(canonicalizer.isUnfiltered("*", new LinkedMultiValueMap<>()));
        assertTrue(canonicalizer.isUnfiltered("all", null));
        assertTrue(canonicalizer.isUnfiltered("*.*.*.*", null));
    }

    @Test
    @DisplayName("a narrowed key or a filter makes the request narrowed")
    void narrowedRequests() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("geo", "EL");

        assertFalse(canonicalizer.isUnfiltered("A...EL", null));
        assertFalse(canonicalizer.isUnfiltered("*", filters));
        assertFalse(canonicalizer.isUnfiltered("A...EL", filters));
    }
}
