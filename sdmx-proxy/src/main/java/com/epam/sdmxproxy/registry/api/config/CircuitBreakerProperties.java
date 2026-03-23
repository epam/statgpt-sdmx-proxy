package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;

/**
 * Configuration properties for circuit breaker settings loaded from application.yaml
 */
@Data
public class CircuitBreakerProperties {
    private Float failureRateThreshold = 50.0f;
    private Integer minimumNumberOfCalls = 10;
    private Long waitDurationInOpenState = 60000L;
    private Integer slidingWindowSize = 10;
}
