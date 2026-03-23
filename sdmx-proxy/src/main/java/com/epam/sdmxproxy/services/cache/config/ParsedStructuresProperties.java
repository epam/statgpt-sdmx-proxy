package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

import java.time.Duration;

/**
 * TTL configuration for parsed structures cache.
 */
@Data
public class ParsedStructuresProperties {
    /**
     * TTL duration (e.g., PT24H for 24 hours)
     */
    private Duration duration = Duration.ofHours(24);

    /**
     * TTL jitter to avoid synchronized expiry (e.g., PT1H for ±1 hour)
     */
    private Duration jitter = Duration.ofHours(1);
}
