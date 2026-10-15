package com.epam.sdmxproxy.services.availability;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * The single definition of "does this availability request carry any narrowing" -- see design 040.
 * <p>
 * Two callers must agree on it, and a disagreement between them would be a silent bug:
 * <ul>
 *   <li>{@code DataQueryAvailabilityEmulator} routes on it -- unfiltered requests may be served
 *       from the content constraint, narrowed ones must be probed.</li>
 *   <li>{@code CacheKeyGenerator} folds the canonical form into the cache key.</li>
 * </ul>
 * Canonicalization is not cosmetic. The consumer issues the unfiltered request from two places in
 * two shapes -- an absent key on the GET route, and a body of {@code {"filters": []}} on the POST
 * route -- and one of those bypasses its own cache entirely. Without collapsing both to the same
 * key, the most expensive request in the system runs twice.
 */
@Component
public class AvailabilityQueryCanonicalizer {

    /**
     * Canonical form of a key that narrows nothing.
     */
    public static final String WILDCARD = "*";

    private static final String ALL_KEYWORD = "all";

    /**
     * Whether the request narrows nothing: no key (or an all-wildcard key) and no filters.
     */
    public boolean isUnfiltered(String key, MultiValueMap<String, String> filters) {
        return isWildcardKey(key) && isEmptyFilters(filters);
    }

    /**
     * Canonical key: {@link #WILDCARD} for anything that narrows nothing, the key verbatim
     * otherwise.
     * <p>
     * Recognized as narrowing nothing: {@code null}, blank, {@code all}, {@code *}, and a
     * positional key whose every position is {@code *} or empty ({@code *.*.*}, {@code ..},
     * {@code *..*}).
     */
    public String canonicalKey(String key) {
        return isWildcardKey(key) ? WILDCARD : key;
    }

    public boolean isWildcardKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String trimmed = key.trim();
        if (WILDCARD.equals(trimmed) || ALL_KEYWORD.equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (trimmed.indexOf('.') < 0) {
            // A single concrete position, e.g. "A" on a one-dimension cube.
            return false;
        }
        // -1 keeps trailing empty positions, so "A.." is not mistaken for "A".
        for (String position : trimmed.split("\\.", -1)) {
            String value = position.trim();
            if (!value.isEmpty() && !WILDCARD.equals(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the filter map narrows nothing. An absent map, an empty map and a map whose every
     * entry has no values are all the same request.
     */
    public boolean isEmptyFilters(MultiValueMap<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }
}
