package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.RegistryCircuitBreakerConfig;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryRateLimitRetryConfig;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistryRetryConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.registry.api.config.CircuitBreakerProperties;
import com.epam.sdmxproxy.registry.api.config.RateLimitRetryProperties;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.config.RetryProperties;
import com.epam.sdmxproxy.registry.api.http.RateLimitRetrySettings;
import com.epam.sdmxproxy.registry.api.util.ConfigUtils;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Factory for creating Resilience4j components (CircuitBreaker, Retry) per registry.
 */
public class Resilience4jComponentFactory {

    private final ResilienceProperties defaultConfig;

    public Resilience4jComponentFactory(ResilienceProperties defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public CircuitBreaker getOrCreateCircuitBreaker(RegistrySelectionResult selectedRegistry, String operationName) {
        if (selectedRegistry == null || selectedRegistry.getRegistryConfiguration() == null || selectedRegistry.getVersionConfiguration() == null) {
            throw new IllegalRegistryConfigurationException("Registry selection result cannot be null and must contain both registry and version configurations");
        }
        if (operationName == null) {
            throw new IllegalRegistryConfigurationException("Operation name cannot be null");
        }
        RegistryConfiguration registryConfig = selectedRegistry.getRegistryConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();

        RegistryResilienceConfig resilienceConfig = versionConfig.getResilienceConfig();
        RegistryCircuitBreakerConfig registryCbConfig = resilienceConfig != null ? resilienceConfig.getCircuitBreaker() : null;
        CircuitBreakerProperties defaultCbConfig = defaultConfig.getDefaultCircuitBreaker();

        float failureRateThreshold = getFailureRateThresholdWithFallbackToDefault(registryCbConfig, defaultCbConfig);
        int minimumNumberOfCalls = getMinimumNumberOfCallsWithFallbackToDefault(registryCbConfig, defaultCbConfig);
        long waitDuration = getWaitDurationWithFallbackToDefault(registryCbConfig, defaultCbConfig);
        int slidingWindowSize = getSlidingWindowSizeWithFallbackToDefault(registryCbConfig, defaultCbConfig);

        CircuitBreakerConfig.Builder circuitBreakerConfigBuilder = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDuration))
                .slidingWindowSize(slidingWindowSize)
                .recordException(upstreamFailurePredicate());
        if (isRateLimitRetryEnabled(versionConfig)) {
            circuitBreakerConfigBuilder.slowCallDurationThreshold(resolveSlowCallDurationThreshold(versionConfig));
        }
        CircuitBreakerConfig circuitBreakerConfig = circuitBreakerConfigBuilder.build();

        String circuitBreakerName = registryConfig.getName() + "-" + versionConfig.getSdmxVersion() + "-" + operationName;
        return CircuitBreaker.of(circuitBreakerName, circuitBreakerConfig);
    }

    public Retry getOrCreateRetry(RegistrySelectionResult selectedRegistry) {
        if (selectedRegistry == null || selectedRegistry.getRegistryConfiguration() == null || selectedRegistry.getVersionConfiguration() == null) {
            throw new IllegalRegistryConfigurationException("Registry selection result cannot be null and must contain both registry and version configurations");
        }
        RegistryConfiguration registryConfig = selectedRegistry.getRegistryConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();

        RegistryResilienceConfig resilienceConfig = versionConfig.getResilienceConfig();
        RegistryRetryConfig registryRetryConfig = resilienceConfig != null ? resilienceConfig.getRetry() : null;
        RetryProperties defaultRetryConfig = defaultConfig.getDefaultRetry();

        int maxAttempts = getMaxAttemptsWithFallbackToDefault(registryRetryConfig, defaultRetryConfig);
        long initialIntervalMillis = getInitialIntervalMillisWithFallbackToDefault(registryRetryConfig, defaultRetryConfig);
        double multiplier = getMultiplierWithFallbackToDefault(registryRetryConfig, defaultRetryConfig);
        long maxIntervalMillis = getMaxIntervalMillisWithFallbackToDefault(registryRetryConfig, defaultRetryConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(
                        IntervalFunction.ofExponentialBackoff(
                                Duration.ofMillis(initialIntervalMillis),
                                multiplier,
                                Duration.ofMillis(maxIntervalMillis)
                        )
                )
                .retryOnException(upstreamFailurePredicate())
                .build();

        return Retry.of(registryConfig.getName() + "-" + versionConfig.getSdmxVersion() + "-retry", retryConfig);
    }

    /**
     * Whether HTTP 429 retrying is enabled for this registry. Opt-in: registries that are not known
     * to rate-limit must not acquire a multi-minute in-request retry loop, nor lose the circuit
     * breaker's slow-call protection.
     */
    public boolean isRateLimitRetryEnabled(VersionSpecificRegistryConfiguration versionConfig) {
        RegistryRateLimitRetryConfig registryConfig = getRateLimitRetryConfig(versionConfig);
        Boolean enabled = registryConfig != null ? registryConfig.getEnabled() : null;
        return enabled != null ? enabled : defaultConfig.getDefaultRateLimitRetry().getEnabled();
    }

    public RateLimitRetrySettings resolveRateLimitRetrySettings(VersionSpecificRegistryConfiguration versionConfig) {
        RegistryRateLimitRetryConfig registryConfig = getRateLimitRetryConfig(versionConfig);
        RateLimitRetryProperties defaults = defaultConfig.getDefaultRateLimitRetry();

        int maxAttempts = ConfigUtils.getWithFallbackToDefault(registryConfig != null ? registryConfig.getMaxAttempts() : null, defaults::getMaxAttempts);
        long initialIntervalMillis = ConfigUtils.getWithFallbackToDefault(registryConfig != null ? registryConfig.getInitialIntervalMillis() : null, defaults::getInitialIntervalMillis);
        double multiplier = ConfigUtils.getWithFallbackToDefault(registryConfig != null ? registryConfig.getMultiplier() : null, defaults::getMultiplier);
        long maxIntervalMillis = ConfigUtils.getWithFallbackToDefault(registryConfig != null ? registryConfig.getMaxIntervalMillis() : null, defaults::getMaxIntervalMillis);
        long maxTotalWaitMillis = ConfigUtils.getWithFallbackToDefault(registryConfig != null ? registryConfig.getMaxTotalWaitMillis() : null, defaults::getMaxTotalWaitMillis);

        return new RateLimitRetrySettings(maxAttempts, initialIntervalMillis, multiplier, maxIntervalMillis, maxTotalWaitMillis);
    }

    /**
     * The circuit breaker times the whole inner call, so when 429 retrying is on it sees the backoff
     * sleeps plus the network time of every attempt. Sizing the threshold against the sleep budget
     * alone would still let a 429 storm open the breaker via slow-call detection.
     */
    private Duration resolveSlowCallDurationThreshold(VersionSpecificRegistryConfiguration versionConfig) {
        RateLimitRetrySettings settings = resolveRateLimitRetrySettings(versionConfig);
        RegistryResilienceConfig resilienceConfig = versionConfig.getResilienceConfig();
        int readTimeout = resilienceConfig != null && resilienceConfig.getReadTimeout() != null
                ? resilienceConfig.getReadTimeout()
                : defaultConfig.getDefaultReadTimeout();
        return Duration.ofMillis(settings.maxTotalWaitMillis() + ((long) settings.maxAttempts() * readTimeout));
    }

    private RegistryRateLimitRetryConfig getRateLimitRetryConfig(VersionSpecificRegistryConfiguration versionConfig) {
        RegistryResilienceConfig resilienceConfig = versionConfig.getResilienceConfig();
        return resilienceConfig != null ? resilienceConfig.getRateLimitRetry() : null;
    }

    private Float getFailureRateThresholdWithFallbackToDefault(RegistryCircuitBreakerConfig registryCbConfig, CircuitBreakerProperties defaultCbConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryCbConfig != null ? registryCbConfig.getFailureRateThreshold() : null,
                defaultCbConfig::getFailureRateThreshold
        );
    }

    private Integer getMinimumNumberOfCallsWithFallbackToDefault(RegistryCircuitBreakerConfig registryCbConfig, CircuitBreakerProperties defaultCbConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryCbConfig != null ? registryCbConfig.getMinimumNumberOfCalls() : null,
                defaultCbConfig::getMinimumNumberOfCalls
        );
    }

    private Long getWaitDurationWithFallbackToDefault(RegistryCircuitBreakerConfig registryCbConfig, CircuitBreakerProperties defaultCbConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryCbConfig != null ? registryCbConfig.getWaitDurationInOpenState() : null,
                defaultCbConfig::getWaitDurationInOpenState
        );
    }

    private Integer getSlidingWindowSizeWithFallbackToDefault(RegistryCircuitBreakerConfig registryCbConfig, CircuitBreakerProperties defaultCbConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryCbConfig != null ? registryCbConfig.getSlidingWindowSize() : null,
                defaultCbConfig::getSlidingWindowSize
        );
    }

    private Integer getMaxAttemptsWithFallbackToDefault(RegistryRetryConfig registryRetryConfig, RetryProperties defaultRetryConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryRetryConfig != null ? registryRetryConfig.getMaxAttempts() : null,
                defaultRetryConfig::getMaxAttempts
        );
    }

    private Long getInitialIntervalMillisWithFallbackToDefault(RegistryRetryConfig registryRetryConfig, RetryProperties defaultRetryConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryRetryConfig != null ? registryRetryConfig.getInitialIntervalMillis() : null,
                defaultRetryConfig::getInitialIntervalMillis
        );
    }

    private Double getMultiplierWithFallbackToDefault(RegistryRetryConfig registryRetryConfig, RetryProperties defaultRetryConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryRetryConfig != null ? registryRetryConfig.getMultiplier() : null,
                defaultRetryConfig::getMultiplier
        );
    }

    private Long getMaxIntervalMillisWithFallbackToDefault(RegistryRetryConfig registryRetryConfig, RetryProperties defaultRetryConfig) {
        return ConfigUtils.getWithFallbackToDefault(
                registryRetryConfig != null ? registryRetryConfig.getMaxIntervalMillis() : null,
                defaultRetryConfig::getMaxIntervalMillis
        );
    }

    /**
     * Predicate identifying exceptions that signal upstream-health failures (network errors, 5xx).
     * Used by both the retry policy (retry only on these) and the circuit breaker (only count
     * these toward the failure rate). Client-error responses such as 4xx are valid registry
     * answers about a client-input domain (e.g. SDMX 404 "No results for query") and must not
     * trip the breaker.
     * <p>
     * HTTP 429 is deliberately excluded. A rate limit is a client-pacing signal, not an
     * upstream-health signal, so it must neither open the breaker nor consume the 5xx retry budget.
     * It is handled one layer down by {@code RateLimitRetryClient}, whose backoff runs on a much
     * longer time scale. Note that excluding it here is not sufficient on its own: a non-recorded
     * exception is still timed by the breaker, so slow-call detection is relaxed as well whenever
     * 429 retrying is enabled -- see {@link #resolveSlowCallDurationThreshold}.
     */
    private Predicate<Throwable> upstreamFailurePredicate() {
        return throwable -> {
            if (throwable instanceof java.io.IOException) {
                return true;
            }
            if (throwable instanceof feign.FeignException feignEx) {
                return feignEx.status() >= 500;
            }
            return false;
        };
    }
}
