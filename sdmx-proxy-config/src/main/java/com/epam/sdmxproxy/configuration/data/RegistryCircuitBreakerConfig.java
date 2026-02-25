package com.epam.sdmxproxy.configuration.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for circuit breaker per registry.
 * All durations are in milliseconds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryCircuitBreakerConfig {
    /**
     * Failure rate threshold percentage (0-100).
     * Circuit opens when failure rate exceeds this threshold.
     * Default: 50.0
     */
    private Float failureRateThreshold = 50.0f;

    /**
     * Minimum number of calls required before circuit can open.
     * Default: 10
     */
    private Integer minimumNumberOfCalls = 10;

    /**
     * Wait duration in open state before transitioning to half-open (milliseconds).
     * Default: 60000 (60 seconds)
     */
    private Long waitDurationInOpenState = 60000L;

    /**
     * Sliding window size for calculating failure rate.
     * Default: 10
     */
    private Integer slidingWindowSize = 10;
}
