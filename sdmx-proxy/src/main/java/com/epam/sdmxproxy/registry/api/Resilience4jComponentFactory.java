package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.RegistryCircuitBreakerConfig;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistryRetryConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.registry.api.config.CircuitBreakerProperties;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.config.RetryProperties;
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

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDuration))
                .slidingWindowSize(slidingWindowSize)
                .recordExceptions(Exception.class)
                .build();

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
                .retryOnException(configureExceptionRetries())
                .build();

        return Retry.of(registryConfig.getName() + "-" + versionConfig.getSdmxVersion() + "-retry", retryConfig);
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

    private Predicate<Throwable> configureExceptionRetries() {
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
