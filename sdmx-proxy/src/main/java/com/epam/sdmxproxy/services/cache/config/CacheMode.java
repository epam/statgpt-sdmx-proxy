package com.epam.sdmxproxy.services.cache.config;

/**
 * Cache mode enumeration.
 * Defines whether to use Redis or local in-memory cache.
 */
public enum CacheMode {
    /**
     * Redis cache mode - uses distributed Redis cache for horizontal scaling
     */
    REDIS,

    /**
     * Local cache mode - uses in-memory Caffeine cache (single instance only)
     */
    LOCAL
}
