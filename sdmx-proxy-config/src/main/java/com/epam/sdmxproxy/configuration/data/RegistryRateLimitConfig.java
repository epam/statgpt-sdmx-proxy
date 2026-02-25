package com.epam.sdmxproxy.configuration.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for rate limiting per registry.
 * All durations are in milliseconds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryRateLimitConfig {
    /**
     * Enable rate limiting for this registry.
     * If null, uses application-wide default (defaultRateLimitingEnabled).
     * Default: null (uses application default)
     */
    private Boolean enabled;

    /**
     * Maximum number of requests allowed in the time period.
     * If null, rate limiting uses default settings.
     */
    private Integer limitForPeriod;

    /**
     * Time period for rate limiting (milliseconds).
     * Default: 60000 (60 seconds = 1 minute)
     */
    private Long limitRefreshPeriod = 60000L;
}
