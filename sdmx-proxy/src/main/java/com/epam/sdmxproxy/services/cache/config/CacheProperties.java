package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for caching.
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdmxproxy.cache")
public class CacheProperties {

    /**
     * Cache mode: redis or local
     */
    private CacheMode mode = CacheMode.LOCAL;

    /**
     * Redis configuration
     */
    private RedisProperties redis = new RedisProperties();

    /**
     * TTL configuration
     */
    private TtlProperties ttl = new TtlProperties();
}
