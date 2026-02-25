package com.epam.sdmxproxy.services.filter;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

/**
 * Implementation of FilterTranslator.
 * Translates SDMX 3.0 c parameter filters to SDMX 2.1 key parameter format.
 * Only works with validated filters (dimensions only, equals operator, singular values).
 */
@Service
public class FilterTranslatorImpl implements FilterTranslator {

    private static final String OPERATOR_SEPARATOR = ":";

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
                String value = extractValue(values.get(0)); // Only first value since we validated singular values
                int dimensionIndex = dimensionOrder.indexOf(dimensionId);

                if (dimensionIndex >= 0) {
                    dimensionValues[dimensionIndex] = value;
                }
            }
        }

        return String.join(".", dimensionValues);
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
