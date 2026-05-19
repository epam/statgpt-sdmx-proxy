package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.RegistryRateLimitConfig;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.RateLimitExceededException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.registry.api.config.RateLimitProperties;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.util.ConfigUtils;
import feign.InvocationHandlerFactory;
import feign.Target;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Custom InvocationHandlerFactory that applies rate limiting to Feign client method calls.
 * Creates rate limiters per method based on registry configuration.
 */
@RequiredArgsConstructor
public class RateLimitingInvocationHandlerFactory implements InvocationHandlerFactory {

    private final RegistrySelectionResult selectedRegistry;
    private final ResilienceProperties resilienceProperties;
    private final InvocationHandlerFactory delegate = new InvocationHandlerFactory.Default();


    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
        Map<Method, MethodHandler> rateLimitedHandlers = getRateLimitedHandlers(target, dispatch);

        return delegate.create(target, rateLimitedHandlers);
    }

    @NotNull
    private Map<Method, MethodHandler> getRateLimitedHandlers(Target target, Map<Method, MethodHandler> dispatch) {
        return dispatch.entrySet().stream()
                .map(entry -> {

                    Method method = entry.getKey();
                    MethodHandler originalHandler = entry.getValue();

                    String rateLimiterName = target.name() + "#" + method.getName();
                    RateLimiter rateLimiter = createRateLimiter(rateLimiterName);

                    MethodHandler rateLimitedHandler = createRateLimitedHandler(originalHandler, rateLimiter, rateLimiterName);

                    return Pair.of(method, rateLimitedHandler);

                })
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    private MethodHandler createRateLimitedHandler(
            MethodHandler originalHandler,
            RateLimiter rateLimiter,
            String rateLimiterName) {

        return (args) -> {
            try {
                return rateLimiter.executeCallable(() -> {
                    try {
                        return originalHandler.invoke(args);
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new UnexpectedStateException(
                                String.format("Rate-limited Feign call '%s' threw a checked exception", rateLimiterName), e);
                    }
                });
            } catch (RequestNotPermitted e) {
                throw new RateLimitExceededException(
                        String.format("Rate limit exceeded for '%s'", rateLimiterName), e);
            }
        };
    }

    private RateLimiter createRateLimiter(String rateLimiterName) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        RegistryResilienceConfig resilienceConfig = versionConfig.getResilienceConfig();
        RegistryRateLimitConfig registryRateLimit = resilienceConfig != null ? resilienceConfig.getRateLimit() : null;
        RateLimitProperties defaultRateLimit = resilienceProperties.getDefaultRateLimit();

        Integer limitForPeriod = ConfigUtils.getWithFallbackToDefault(
                registryRateLimit != null ? registryRateLimit.getLimitForPeriod() : null,
                defaultRateLimit::getLimitForPeriod
        );

        Long limitRefreshPeriod = ConfigUtils.getWithFallbackToDefault(
                registryRateLimit != null ? registryRateLimit.getLimitRefreshPeriod() : null,
                defaultRateLimit::getLimitRefreshPeriod
        );

        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofMillis(limitRefreshPeriod))
                .timeoutDuration(Duration.ZERO)
                .build();

        return RateLimiter.of(rateLimiterName, rateLimiterConfig);
    }

}
