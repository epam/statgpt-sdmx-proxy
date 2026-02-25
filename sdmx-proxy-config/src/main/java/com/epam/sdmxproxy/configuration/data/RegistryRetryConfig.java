package com.epam.sdmxproxy.configuration.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for retry per registry.
 * All durations are in milliseconds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryRetryConfig {
    /**
     * Maximum number of retry attempts.
     * If null, default retry max attempts will be used.
     */
    private Integer maxAttempts;

    /**
     * Initial interval for exponential backoff in milliseconds.
     * If null, default initial interval will be used.
     */
    private Long initialIntervalMillis;

    /**
     * Multiplier for exponential backoff.
     * If null, default multiplier will be used.
     */
    private Double multiplier;

    /**
     * Maximum interval for exponential backoff in milliseconds.
     * If null, default max interval will be used.
     */
    private Long maxIntervalMillis;
}
