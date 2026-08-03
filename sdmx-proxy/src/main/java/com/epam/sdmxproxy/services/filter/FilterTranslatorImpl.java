package com.epam.sdmxproxy.services.filter;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Implementation of FilterTranslator.
 * Translates SDMX 3.0 c parameter filters to SDMX 2.1 key parameter format.
 * Only works with validated filters (dimensions only, equals operator).
 */
@Service
public class FilterTranslatorImpl implements FilterTranslator {

    private static final String OPERATOR_SEPARATOR = ":";

    /** SDMX 3.0 OR separator, as it arrives on the proxy's own interface. */
    private static final String MULTIPLE_VALUES_SEPARATOR = ",";

    /** SDMX 2.1 OR separator, as it must leave in a key position. */
    private static final String OR_SEPARATOR = "+";

    @Override
    public String mergeFiltersIntoKey(String key, MultiValueMap<String, String> filters, List<String> dimensionOrder) {
        if (filters == null || filters.isEmpty()) {
            return key;
        }

        // Parse existing key into dimension values
        String[] keyParts = key != null ? key.split("\\.") : new String[0];

        // Create array to hold dimension values
        String[] dimensionValues = new String[dimensionOrder.size()];

        // Initialize with existing key values
        System.arraycopy(keyParts, 0, dimensionValues, 0, Math.min(keyParts.length, dimensionValues.length));

        // Fill remaining with wildcards if key was shorter
        for (int i = keyParts.length; i < dimensionValues.length; i++) {
            dimensionValues[i] = "*";
        }

        // Apply filters
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            String dimensionId = entry.getKey();
            List<String> values = entry.getValue();

            if (values != null && !values.isEmpty()) {
                int dimensionIndex = dimensionOrder.indexOf(dimensionId);

                if (dimensionIndex >= 0) {
                    dimensionValues[dimensionIndex] = toKeyPosition(values);
                }
            }
        }

        return String.join(".", dimensionValues);
    }

    /**
     * Renders one dimension's narrowing as a single SDMX 2.1 key position.
     *
     * <p>The proxy's own interface is SDMX 3.0, where alternatives for a Component are OR-joined
     * with {@code ,} -- either within one parameter ({@code c[X]=A,B}) or by repeating it
     * ({@code c[X]=A&c[X]=B}). SDMX 2.1 spells the same OR as {@code +} inside the key position,
     * so both shapes collapse into one {@code +}-joined position here.
     *
     * <p>Verified against OECD: {@code ITA.M...C+F+GTU....} returns obs_count=10200 with
     * ACTIVITY narrowed to exactly C, F and GTU.
     */
    private String toKeyPosition(List<String> values) {
        LinkedHashSet<String> alternatives = new LinkedHashSet<>();
        for (String value : values) {
            for (String alternative : value.split(MULTIPLE_VALUES_SEPARATOR)) {
                String extracted = extractValue(alternative).trim();
                if (!extracted.isEmpty()) {
                    alternatives.add(extracted);
                }
            }
        }
        return String.join(OR_SEPARATOR, alternatives);
    }

    /**
     * Extracts the actual value from a filter value string, removing operator prefix if present.
     * Example: "eq:A" -> "A", "A" -> "A"
     */
    private String extractValue(String filterValue) {
        int separatorIndex = filterValue.indexOf(OPERATOR_SEPARATOR);
        if (separatorIndex > 0) {
            return filterValue.substring(separatorIndex + 1);
        }
        return filterValue;
    }
}
