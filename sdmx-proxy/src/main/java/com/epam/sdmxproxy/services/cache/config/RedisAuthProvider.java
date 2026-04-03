package com.epam.sdmxproxy.services.cache.config;

/**
 * Supported cloud authentication providers for Redis.
 */
public enum RedisAuthProvider {
    NONE,
    AZURE,
    AWS,
    GCP
}
