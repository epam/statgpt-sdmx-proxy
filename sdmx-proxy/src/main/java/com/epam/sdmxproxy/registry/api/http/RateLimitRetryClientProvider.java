package com.epam.sdmxproxy.registry.api.http;

import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.registry.api.Resilience4jComponentFactory;
import feign.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Wraps a Feign {@link Client} with {@link RateLimitRetryClient} for registries that opt into HTTP
 * 429 retrying.
 * <p>
 * Kept out of {@code Resilience4jComponentFactory}, whose role is minting resilience4j components --
 * this wrapper contains no resilience4j at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitRetryClientProvider {

    private final Resilience4jComponentFactory resilience4JComponentFactory;
    private final Sleeper sleeper;

    /**
     * Returns the delegate unchanged when 429 retrying is disabled for this registry, so registries
     * that are not known to rate-limit keep their existing behaviour exactly.
     */
    public Client wrap(Client delegate, RegistrySelectionResult selectedRegistry) {
        if (!resilience4JComponentFactory.isRateLimitRetryEnabled(selectedRegistry.getVersionConfiguration())) {
            return delegate;
        }
        RateLimitRetrySettings settings = resilience4JComponentFactory.resolveRateLimitRetrySettings(selectedRegistry.getVersionConfiguration());
        log.debug("HTTP 429 retry enabled for registry {}: {}", selectedRegistry.getRegistryConfiguration().getName(), settings);
        return new RateLimitRetryClient(delegate, settings, sleeper);
    }
}
