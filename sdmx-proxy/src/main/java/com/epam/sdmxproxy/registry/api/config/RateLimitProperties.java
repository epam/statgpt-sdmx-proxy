package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;

/**
 * Configuration properties for rate limiting settings loaded from application.yaml
 */
@Data
public class RateLimitProperties {
    /**
     * Maximum number of requests allowed in the time period.
     * Default: 100 requests per period.
     */
    private Integer limitForPeriod = 100;

    /**
     * Time period for rate limiting in milliseconds.
     * Default: 60000 (60 seconds = 1 minute)
     */
    private Long limitRefreshPeriod = 60000L;
}
