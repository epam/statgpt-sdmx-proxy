package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based cache service implementation.
 * Uses RedisTemplate for explicit control over serialization and TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sdmxproxy.cache.mode", havingValue = "redis")
public class RedisCacheService implements CacheService {

    private static final String RAW_STRUCTURES_PREFIX = "raw:";
    private static final String READY_RESPONSE_PREFIX = "response:";

    private final RedisTemplate<String, byte[]> rawStructuresRedisTemplate;
    private final RedisTemplate<String, byte[]> readyResponseRedisTemplate;
    private final CacheProperties cacheProperties;

    @Override
    public Optional<byte[]> getRawStructures(String key) {
        try {
            String redisKey = RAW_STRUCTURES_PREFIX + key;
            byte[] value = rawStructuresRedisTemplate.opsForValue().get(redisKey);
            if (value != null) {
                log.debug("Cache hit for parsed structures: {}", key);
                return Optional.of(value);
            }
            log.debug("Cache miss for parsed structures: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error getting parsed structures from Redis cache: {}", key, e);
            throw new RuntimeException("Redis cache operation failed", e);
        }
    }

    @Override
    public void putRawStructures(String key, byte[] structuresBytes) {
        try {
            long ttlSeconds = cacheProperties.getTtl().getParsedStructures().getDuration().getSeconds();
            String redisKey = RAW_STRUCTURES_PREFIX + key;
            long ttlWithJitter = addJitter(ttlSeconds, cacheProperties.getTtl().getParsedStructures().getJitter().getSeconds());
            rawStructuresRedisTemplate.opsForValue().set(redisKey, structuresBytes, ttlWithJitter, TimeUnit.SECONDS);
            log.debug("Cached parsed structures: {} (TTL: {}s)", key, ttlWithJitter);
        } catch (Exception e) {
            log.error("Error putting parsed structures into Redis cache: {}", key, e);
            throw new RuntimeException("Redis cache operation failed", e);
        }
    }

    @Override
    public Optional<byte[]> getReadyResponse(String key) {
        try {
            String redisKey = READY_RESPONSE_PREFIX + key;
            byte[] value = readyResponseRedisTemplate.opsForValue().get(redisKey);
            if (value != null) {
                log.debug("Cache hit for ready response: {}", key);
                return Optional.of(value);
            }
            log.debug("Cache miss for ready response: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error getting ready response from Redis cache: {}", key, e);
            throw new RuntimeException("Redis cache operation failed", e);
        }
    }

    @Override
    public void putReadyResponse(String key, byte[] responseBytes) {
        try {
            long ttlSeconds = cacheProperties.getTtl().getReadyResponses().getDuration().getSeconds();
            String redisKey = READY_RESPONSE_PREFIX + key;
            long ttlWithJitter = addJitter(ttlSeconds, cacheProperties.getTtl().getReadyResponses().getJitter().getSeconds());
            readyResponseRedisTemplate.opsForValue().set(redisKey, responseBytes, ttlWithJitter, TimeUnit.SECONDS);
            log.debug("Cached ready response: {} (TTL: {}s, size: {} bytes)", key, ttlWithJitter, responseBytes.length);
        } catch (Exception e) {
            log.error("Error putting ready response into Redis cache: {}", key, e);
            throw new RuntimeException("Redis cache operation failed", e);
        }
    }

    /**
     * Add jitter to TTL to avoid synchronized expiry.
     *
     * @param baseTtl       base TTL in seconds
     * @param jitterSeconds jitter range in seconds (±jitterSeconds)
     * @return TTL with jitter applied
     */
    private long addJitter(long baseTtl, long jitterSeconds) {
        if (jitterSeconds <= 0) {
            return baseTtl;
        }
        // Random jitter: baseTtl ± random(0, jitterSeconds)
        long jitter = (long) (Math.random() * jitterSeconds * 2) - jitterSeconds;
        return Math.max(1, baseTtl + jitter);
    }
}
