package com.epam.sdmxproxy.services.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.epam.sdmxproxy.exception.CacheUnavailableException;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

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
    private static final String LIMIT_EMULATION_PREFIX = "limit_emu:";
    private static final String AVAILABILITY_EMULATION_PREFIX = "avail_emu:";
    private static final int SCAN_BATCH_SIZE = 500;

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
            throw new CacheUnavailableException("Redis cache operation failed", e);
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
            throw new CacheUnavailableException("Redis cache operation failed", e);
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
            throw new CacheUnavailableException("Redis cache operation failed", e);
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
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    @Override
    public Optional<byte[]> getLimitEmulationShrinkFilters(String key) {
        try {
            String redisKey = LIMIT_EMULATION_PREFIX + key;
            byte[] value = readyResponseRedisTemplate.opsForValue().get(redisKey);
            if (value != null) {
                log.debug("Cache hit for limit emulation: {}", key);
                return Optional.of(value);
            }
            log.debug("Cache miss for limit emulation: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error getting limit emulation entry from Redis cache: {}", key, e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    @Override
    public void putLimitEmulationShrinkFilters(String key, byte[] value) {
        try {
            long ttlSeconds = cacheProperties.getTtl().getLimitEmulation().getDuration().getSeconds();
            String redisKey = LIMIT_EMULATION_PREFIX + key;
            long ttlWithJitter = addJitter(ttlSeconds, cacheProperties.getTtl().getLimitEmulation().getJitter().getSeconds());
            readyResponseRedisTemplate.opsForValue().set(redisKey, value, ttlWithJitter, TimeUnit.SECONDS);
            log.debug("Cached limit emulation entry: {} (TTL: {}s, size: {} bytes)", key, ttlWithJitter, value.length);
        } catch (Exception e) {
            log.error("Error putting limit emulation entry into Redis cache: {}", key, e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    @Override
    public Optional<byte[]> getEmulatedAvailability(String key) {
        try {
            String redisKey = AVAILABILITY_EMULATION_PREFIX + key;
            byte[] value = readyResponseRedisTemplate.opsForValue().get(redisKey);
            if (value != null) {
                log.debug("Cache hit for emulated availability: {}", key);
                return Optional.of(value);
            }
            log.debug("Cache miss for emulated availability: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error getting emulated availability from Redis cache: {}", key, e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    @Override
    public void putEmulatedAvailability(String key, byte[] responseBytes) {
        try {
            long ttlSeconds = cacheProperties.getTtl().getAvailabilityEmulation().getDuration().getSeconds();
            String redisKey = AVAILABILITY_EMULATION_PREFIX + key;
            long ttlWithJitter = addJitter(ttlSeconds, cacheProperties.getTtl().getAvailabilityEmulation().getJitter().getSeconds());
            readyResponseRedisTemplate.opsForValue().set(redisKey, responseBytes, ttlWithJitter, TimeUnit.SECONDS);
            log.debug("Cached emulated availability: {} (TTL: {}s, size: {} bytes)", key, ttlWithJitter, responseBytes.length);
        } catch (Exception e) {
            log.error("Error putting emulated availability into Redis cache: {}", key, e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    /**
     * Deletes every key the proxy owns, matched by the three prefixes this class writes under.
     * <p>
     * Scans rather than calling {@code KEYS}, which blocks the server, and never calls
     * {@code FLUSHDB}: the Redis instance can be shared, so wiping the whole database would take
     * other tenants' data with it.
     */
    @Override
    public void invalidateAll() {
        try {
            long deleted = deleteByPrefix(rawStructuresRedisTemplate, RAW_STRUCTURES_PREFIX) + deleteByPrefix(readyResponseRedisTemplate, READY_RESPONSE_PREFIX) + deleteByPrefix(readyResponseRedisTemplate, LIMIT_EMULATION_PREFIX) + deleteByPrefix(readyResponseRedisTemplate, AVAILABILITY_EMULATION_PREFIX);
            log.info("Cache invalidated: registry configuration changed, {} keys deleted", deleted);
        } catch (Exception e) {
            log.error("Failed to invalidate Redis cache after a configuration change", e);
            throw new CacheUnavailableException("Redis cache operation failed", e);
        }
    }

    private long deleteByPrefix(RedisTemplate<String, byte[]> template, String prefix) {
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(SCAN_BATCH_SIZE).build();
        List<String> batch = new ArrayList<>();
        long deleted = 0L;
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH_SIZE) {
                    deleted += deleteBatch(template, batch);
                }
            }
        }
        deleted += deleteBatch(template, batch);
        return deleted;
    }

    private long deleteBatch(RedisTemplate<String, byte[]> template, List<String> batch) {
        if (batch.isEmpty()) {
            return 0L;
        }
        Long removed = template.delete(batch);
        batch.clear();
        return removed == null ? 0L : removed;
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
