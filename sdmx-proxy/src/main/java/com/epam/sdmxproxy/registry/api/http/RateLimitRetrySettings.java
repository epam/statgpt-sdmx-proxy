package com.epam.sdmxproxy.registry.api.http;

/**
 * Resolved HTTP 429 retry settings for a single registry, after per-registry configuration has been
 * merged with the application-wide defaults.
 *
 * @param maxAttempts           attempts per 429 cycle, including the initial call
 * @param initialIntervalMillis first backoff interval
 * @param multiplier            exponential-backoff multiplier
 * @param maxIntervalMillis     ceiling for a single wait, including one derived from {@code Retry-After}
 * @param maxTotalWaitMillis    ceiling for the summed wait across one cycle
 */
public record RateLimitRetrySettings(
        int maxAttempts,
        long initialIntervalMillis,
        double multiplier,
        long maxIntervalMillis,
        long maxTotalWaitMillis
) {
}
