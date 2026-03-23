package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

import java.time.Duration;

/**
 * TTL configuration for ready responses cache.
 */
@Data
public class ReadyResponsesProperties {
    /**
     * TTL duration (e.g., PT6H for 6 hours)
     */
    private Duration duration = Duration.ofHours(6);

    /**
     * TTL jitter to avoid synchronized expiry (e.g., PT30M for ±30 minutes)
     */
    private Duration jitter = Duration.ofMinutes(30);
}
