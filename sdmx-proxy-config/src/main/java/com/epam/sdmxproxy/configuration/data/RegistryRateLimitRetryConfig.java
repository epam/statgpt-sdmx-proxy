package com.epam.sdmxproxy.configuration.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for retrying HTTP 429 (rate-limit) responses per registry.
 * All durations are in milliseconds.
 * <p>
 * Kept separate from {@link RegistryRetryConfig} because rate-limit backoff operates on a much
 * longer time scale than 5xx backoff (minutes vs seconds), and because it carries two fields that
 * must not leak onto the 5xx retry: {@link #enabled} and {@link #maxTotalWaitMillis}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryRateLimitRetryConfig {

    /**
     * Enable retrying HTTP 429 responses for this registry.
     * If null, uses the application-wide default (which is false).
     */
    private Boolean enabled;

    /**
     * Maximum attempts per 429 cycle, including the initial call.
     * If null, the application default is used.
     */
    private Integer maxAttempts;

    /**
     * Initial interval for exponential backoff in milliseconds.
     * If null, the application default is used.
     */
    private Long initialIntervalMillis;

    /**
     * Multiplier for exponential backoff.
     * If null, the application default is used.
     */
    private Double multiplier;

    /**
     * Ceiling for a single wait, including a wait derived from a {@code Retry-After} header.
     * If null, the application default is used.
     */
    private Long maxIntervalMillis;

    /**
     * Ceiling for the summed wait across one 429 cycle. Bounds the worst case when a registry
     * returns an inflated {@code Retry-After}.
     * If null, the application default is used.
     */
    private Long maxTotalWaitMillis;
}
