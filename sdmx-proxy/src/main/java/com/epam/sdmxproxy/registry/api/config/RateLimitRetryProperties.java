package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;

/**
 * Configuration properties for the HTTP 429 (rate-limit) retry defaults loaded from application.yaml.
 * <p>
 * A peer of {@link RetryProperties} rather than a subclass: it carries {@code enabled} and
 * {@code maxTotalWaitMillis}, which have no meaning for the 5xx retry family.
 */
@Data
public class RateLimitRetryProperties {

    /**
     * Enable retrying HTTP 429 responses application-wide.
     * Opt-in by design: registries that are not known to rate-limit must not acquire a
     * multi-minute in-request retry loop.
     * Default: false
     */
    private Boolean enabled = false;

    /**
     * Maximum attempts per 429 cycle, including the initial call.
     * Default: 5 (four waits)
     */
    private Integer maxAttempts = 5;

    /**
     * Initial interval for exponential backoff in milliseconds.
     * Default: 5000
     */
    private Long initialIntervalMillis = 5000L;

    /**
     * Multiplier for exponential backoff.
     * Default: 2.0
     */
    private Double multiplier = 2.0;

    /**
     * Ceiling for a single wait, including a wait derived from a {@code Retry-After} header.
     * Default: 60000 (one full rate-limit window)
     */
    private Long maxIntervalMillis = 60000L;

    /**
     * Ceiling for the summed wait across one 429 cycle.
     * Default: 75000 -- the exact sum of the default backoff sequence (5 + 10 + 20 + 40 s), so the
     * budget actually binds instead of being slack.
     */
    private Long maxTotalWaitMillis = 75000L;
}
