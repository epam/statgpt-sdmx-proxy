package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.exception.RegistryCoolingDownException;
import com.epam.sdmxproxy.exception.StructureConversionException;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.singleflight.RequestCoalescer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import feign.FeignException;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Raw-structures cache, rate-limit cooldown and single-flight in front of
 * {@link GenericRegistryAdapter#getStructures}. Caches the registry's bytes verbatim; fixtures are
 * the caller's business. See design 042.
 */
@Slf4j
@Service
public class RawStructureFetcherImpl implements RawStructureFetcher {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final AttributeKey<String> REGISTRY = AttributeKey.stringKey("registry");

    private final GenericRegistryAdapter genericRegistryAdapter;
    private final CacheService cacheService;
    private final RequestCoalescer requestCoalescer;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;
    private final Duration rateLimitCooldown;
    private final Cache<String, Boolean> coolingDown;
    private final LongCounter upstreamCounter;
    private final LongCounter cooldownRejectionCounter;

    @Autowired
    public RawStructureFetcherImpl(GenericRegistryAdapter genericRegistryAdapter, CacheService cacheService, RequestCoalescer requestCoalescer, Sdmx21QueryNormalizer sdmx21QueryNormalizer, CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry) {
        this(genericRegistryAdapter, cacheService, requestCoalescer, sdmx21QueryNormalizer, cacheProperties, openTelemetry, Ticker.systemTicker());
    }

    RawStructureFetcherImpl(GenericRegistryAdapter genericRegistryAdapter, CacheService cacheService, RequestCoalescer requestCoalescer, Sdmx21QueryNormalizer sdmx21QueryNormalizer, CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry, Ticker ticker) {
        this.genericRegistryAdapter = genericRegistryAdapter;
        this.cacheService = cacheService;
        this.requestCoalescer = requestCoalescer;
        this.sdmx21QueryNormalizer = sdmx21QueryNormalizer;
        this.rateLimitCooldown = cacheProperties.getSingleFlight().getRateLimitCooldown();
        this.coolingDown = Caffeine.newBuilder().expireAfterWrite(rateLimitCooldown).ticker(ticker).build();
        Meter meter = openTelemetry.getIfAvailable(OpenTelemetry::noop).getMeter("sdmx-proxy.single-flight");
        this.upstreamCounter = meter.counterBuilder("sdmxproxy.registry.structure_requests").setDescription("Structure requests actually sent to a registry").build();
        this.cooldownRejectionCounter = meter.counterBuilder("sdmxproxy.singleflight.cooldown_rejections").setDescription("Structure fetches refused during a rate-limit cooldown").build();
    }

    @Override
    public byte[] fetch(TranslatedStructureQuery query) {
        String key = CacheKeyGenerator.generateStructureKey(query, sdmx21QueryNormalizer);
        Optional<byte[]> cached = cacheService.getRawStructures(key);
        if (cached.isPresent()) {
            log.debug("Cache hit for raw structures: {}", key);
            return cached.get();
        }
        throwIfCoolingDown(key);
        return requestCoalescer.coalesce(key, () -> load(query, key));
    }

    private byte[] load(TranslatedStructureQuery query, String key) {
        // The value may have landed while this flight waited for an executor slot or, after the
        // move to Redis, while another instance fetched it.
        Optional<byte[]> cached = cacheService.getRawStructures(key);
        if (cached.isPresent()) {
            log.debug("Raw structures appeared before the upstream call: {}", key);
            return cached.get();
        }
        throwIfCoolingDown(key);

        String registryName = query.getRegistryConfiguration().getName();
        upstreamCounter.add(1, Attributes.of(REGISTRY, registryName));
        log.info("Single-flight: calling registry {} for {}", registryName, key);
        try (InputStream in = genericRegistryAdapter.getStructures(query)) {
            if (in == null) {
                log.debug("Registry returned no body (e.g. HTTP 204) for raw structures: {}", key);
                return new byte[0];
            }
            byte[] bytes = in.readAllBytes();
            cacheService.putRawStructures(key, bytes);
            return bytes;
        } catch (FeignException e) {
            if (e.status() == TOO_MANY_REQUESTS) {
                coolingDown.put(key, Boolean.TRUE);
                log.warn("Registry {} still answered 429 after the retry budget for {}; cooling down for {}", registryName, key, rateLimitCooldown);
            }
            throw e;
        } catch (IOException e) {
            throw new StructureConversionException("Failed to read structures", e);
        }
    }

    private void throwIfCoolingDown(String key) {
        if (coolingDown.getIfPresent(key) != null) {
            cooldownRejectionCounter.add(1);
            throw new RegistryCoolingDownException("Registry rate limit cooldown active for " + key);
        }
    }
}
