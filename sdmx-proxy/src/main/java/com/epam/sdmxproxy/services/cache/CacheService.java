package com.epam.sdmxproxy.services.cache;

import java.util.Optional;

/**
 * Cache service interface for caching parsed structures and ready responses.
 * Supports two cache domains:
 * 1. Parsed structures (POJO) - for internal reuse
 * 2. Ready responses (bytes) - for external responses
 * <p>
 * Implementation can be Redis-based or in-memory (Caffeine).
 */
public interface CacheService {

    /**
     * Get parsed structures from cache.
     *
     * @param key cache key for structures
     * @return Optional containing SdmxBeans if found in cache, empty otherwise
     */
    Optional<byte[]> getRawStructures(String key);

    /**
     * Put parsed structures into cache.
     *
     * @param key             cache key for structures
     * @param structuresBytes raw structures to cache
     */
    void putRawStructures(String key, byte[] structuresBytes);

    /**
     * Get ready response bytes from cache.
     *
     * @param key cache key for response
     * @return Optional containing response bytes if found in cache, empty otherwise
     */
    Optional<byte[]> getReadyResponse(String key);

    /**
     * Put ready response bytes into cache.
     *
     * @param key           cache key for response
     * @param responseBytes response bytes to cache
     */
    void putReadyResponse(String key, byte[] responseBytes);

    /**
     * Get a cached limit-emulation shrunk-query result. Each entry encodes the shrunk
     * key + shrunk filter map produced by the bisect for a specific
     * (dataset, key, filters, limit) request shape, letting subsequent identical
     * requests skip the availability-probe loop entirely.
     *
     * @param key cache key for the limit-emulation entry
     * @return Optional containing the serialized shrink result if found, empty otherwise
     */
    Optional<byte[]> getLimitEmulationShrinkFilters(String key);

    /**
     * Put a limit-emulation shrunk-query result into cache.
     *
     * @param key   cache key
     * @param value serialized shrink result
     */
    void putLimitEmulationShrinkFilters(String key, byte[] value);

}
