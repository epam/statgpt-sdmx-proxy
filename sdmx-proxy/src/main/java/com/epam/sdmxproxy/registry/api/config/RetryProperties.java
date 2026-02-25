package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;

/**
 * Configuration properties for retry settings loaded from application.yaml
 */
@Data
public class RetryProperties {
    /**
     * Maximum number of retry attempts.
     * Default: 5
     */
    private Integer maxAttempts = 5;

    /**
     * Initial interval for exponential backoff in milliseconds.
     * Default: 500
     */
    private Long initialIntervalMillis = 500L;

    /**
     * Multiplier for exponential backoff.
     * Default: 2.0
     */
    private Double multiplier = 2.0;

    /**
     * Maximum interval for exponential backoff in milliseconds.
     * Default: 2000 (2 seconds)
     */
    private Long maxIntervalMillis = 2000L;
}
