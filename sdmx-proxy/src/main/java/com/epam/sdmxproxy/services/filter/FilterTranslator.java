package com.epam.sdmxproxy.services.filter;

import org.springframework.util.MultiValueMap;

import java.util.List;

/**
 * Translates SDMX 3.0 c parameter filters to SDMX 2.1 key parameter format.
 * Only works with validated filters (dimensions only, equals operator, singular values).
 */
public interface FilterTranslator {

    /**
     * Merges c parameter filters with the existing key parameter.
     * Filters are assumed to be validated (dimensions only, equals operator, singular values).
     *
     * @param key            Existing key parameter (may be null or contain wildcards)
     * @param filters        Validated filters from c parameter
     * @param dimensionOrder Ordered list of dimension IDs as they appear in the key
     * @return Merged key string for SDMX 2.1 API
     */
    String mergeFiltersIntoKey(String key, MultiValueMap<String, String> filters, List<String> dimensionOrder);
}
