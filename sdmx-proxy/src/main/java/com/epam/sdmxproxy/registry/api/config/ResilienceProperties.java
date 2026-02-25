package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for default resilience settings loaded from application.yaml
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdmxproxy.registry.resilience")
public class ResilienceProperties {
    private Integer defaultConnectionTimeout = 30000;
    private Integer defaultReadTimeout = 30000;
    private CircuitBreakerProperties defaultCircuitBreaker = new CircuitBreakerProperties();
    private RetryProperties defaultRetry = new RetryProperties();
    private RateLimitProperties defaultRateLimit = new RateLimitProperties();

    /**
     * Enable rate limiting application-wide by default.
     * If false, rate limiting is disabled unless explicitly enabled per registry.
     * Default: false
     */
    private Boolean defaultRateLimitingEnabled = false;
}
