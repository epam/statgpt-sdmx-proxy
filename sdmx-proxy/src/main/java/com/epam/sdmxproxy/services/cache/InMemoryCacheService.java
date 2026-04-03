package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache service implementation using Caffeine.
 * Used when cache mode is 'local' (single instance deployment).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sdmxproxy.cache.mode", havingValue = "local", matchIfMissing = true)
public class InMemoryCacheService implements CacheService {

    private final Cache<String, byte[]> rawStructuresCache;
    private final Cache<String, byte[]> readyResponseCache;

    public InMemoryCacheService(CacheProperties cacheProperties) {
        // Configure parsed structures cache with TTL
        Duration parsedStructuresTtl = cacheProperties.getTtl().getParsedStructures().getDuration();
        this.rawStructuresCache = Caffeine.newBuilder()
                .expireAfterWrite(parsedStructuresTtl)
                .build();

        // Configure ready response cache with TTL
        Duration readyResponseTtl = cacheProperties.getTtl().getReadyResponses().getDuration();
        this.readyResponseCache = Caffeine.newBuilder()
                .expireAfterWrite(readyResponseTtl)
                .build();

        log.info("In-memory cache initialized (Caffeine) - Parsed structures TTL: {}, Ready responses TTL: {}", parsedStructuresTtl, readyResponseTtl);
    }

    @Override
    public Optional<byte[]> getRawStructures(String key) {
        byte[] value = rawStructuresCache.getIfPresent(key);
        if (value != null) {
            log.debug("Cache hit for parsed structures: {}", key);
            return Optional.of(value);
        }
        log.debug("Cache miss for parsed structures: {}", key);
        return Optional.empty();
    }

    @Override
    public void putRawStructures(String key, byte[] structuresBytes) {
        rawStructuresCache.put(key, structuresBytes);
    }

    @Override
    public Optional<byte[]> getReadyResponse(String key) {
        byte[] value = readyResponseCache.getIfPresent(key);
        if (value != null) {
            log.debug("Cache hit for ready response: {}", key);
            return Optional.of(value);
        }
        log.debug("Cache miss for ready response: {}", key);
        return Optional.empty();
    }

    @Override
    public void putReadyResponse(String key, byte[] responseBytes) {
        readyResponseCache.put(key, responseBytes);
    }

}
