package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

/**
 * Resilience configuration for a registry.
 * Contains all resilience-related settings: timeouts, circuit breaker, retry, and rate limiting.
 */
@Data
public class RegistryResilienceConfig {
    /**
     * Connection timeout in milliseconds.
     * Default: 30000 (30 seconds)
     */
    private Integer connectionTimeout;

    /**
     * Read timeout in milliseconds.
     * Default: 30000 (30 seconds)
     */
    private Integer readTimeout;

    /**
     * Circuit breaker configuration.
     * If null, default circuit breaker settings will be used.
     */
    private RegistryCircuitBreakerConfig circuitBreaker;

    /**
     * Retry configuration.
     * If null, default retry settings will be used.
     */
    private RegistryRetryConfig retry;

    /**
     * Rate limiting configuration.
     * If null, rate limiting uses default settings (may be disabled).
     */
    private RegistryRateLimitConfig rateLimit;
}
