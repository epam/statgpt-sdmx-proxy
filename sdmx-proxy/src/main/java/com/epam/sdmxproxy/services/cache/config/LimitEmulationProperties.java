package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

import java.time.Duration;

/**
 * TTL configuration for the limit-emulation shrunk-query cache. Each emulation run
 * incurs several availability probes (one HTTP round-trip each). When two requests with
 * the same dataset/key/filters/limit shape arrive in close succession, caching the
 * shrunk filter map skips the entire bisect and shaves seconds off the second call.
 * <p>
 * Default TTL is short (1 hour) because the bisect outcome depends on registry-side
 * cube density, which can drift as new series are published. Short TTL bounds how
 * stale a cached shrink can be.
 */
@Data
public class LimitEmulationProperties {

    private Duration duration = Duration.ofHours(1);

    private Duration jitter = Duration.ofMinutes(10);
}
