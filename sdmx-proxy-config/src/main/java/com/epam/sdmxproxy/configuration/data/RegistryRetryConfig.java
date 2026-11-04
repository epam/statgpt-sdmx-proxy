package com.epam.sdmxproxy.configuration.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

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

    /**
     * Upstream HTTP statuses retried in addition to 5xx and I/O failures. They don't count as
     * failures for the circuit breaker. Statistics Canada answers 409 while a table is being
     * updated -- see design 043. Null or empty retries only 5xx and I/O failures.
     */
    private Set<Integer> retryOnStatuses;
}
