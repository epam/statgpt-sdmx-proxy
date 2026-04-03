package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

/**
 * TTL configuration for cache domains.
 */
@Data
public class TtlProperties {
    /**
     * TTL configuration for parsed structures
     */
    private ParsedStructuresProperties parsedStructures = new ParsedStructuresProperties();

    /**
     * TTL configuration for ready responses
     */
    private ReadyResponsesProperties readyResponses = new ReadyResponsesProperties();
}
