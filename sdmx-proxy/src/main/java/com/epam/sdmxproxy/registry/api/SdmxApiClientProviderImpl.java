package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30StructureClient;
import com.epam.sdmxproxy.registry.api.client.SdmxApiBase;
import com.epam.sdmxproxy.registry.api.config.InputStreamFeignDecoder;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.config.Slf4jFeignLogger;
import com.epam.sdmxproxy.registry.api.http.RateLimitRetryClientProvider;
import feign.Client;
import feign.Feign;
import feign.InvocationHandlerFactory;
import feign.Logger;
import feign.Request;
import feign.codec.Encoder;
import io.github.resilience4j.feign.FeignDecorators;
import io.github.resilience4j.feign.Resilience4jFeign;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class SdmxApiClientProviderImpl implements SdmxApiClientProvider {

    private static final String BUILT_API_CLIENT_KEY_FORMAT = "%s_____%s_____%s";
    private final Encoder encoder;
    private final InputStreamFeignDecoder decoder;
    private final Client baseOkHttpClient;
    private final ResilienceProperties resilienceConfig;
    private final Resilience4jComponentFactory resilience4JComponentFactory;
    private final RateLimitRetryClientProvider rateLimitRetryClientProvider;

    private final ConcurrentHashMap<String, SdmxApiBase> builtApiClients = new ConcurrentHashMap<>();

    @Override
    public Sdmx21AvailabilityClient getAvailability21Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateAvailabilityEndpointConfig(selectedRegistry);
        String availabilityUrl = selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx21AvailabilityClient.class, availabilityUrl, selectedRegistry);
    }

    @Override
    public Sdmx30AvailabilityClient getAvailability30Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateAvailabilityEndpointConfig(selectedRegistry);
        String availabilityUrl = selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx30AvailabilityClient.class, availabilityUrl, selectedRegistry);
    }

    @Override
    public Sdmx21DataClient getData21Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateDataEndpointConfig(selectedRegistry);
        String url = selectedRegistry.getVersionConfiguration().getDataEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx21DataClient.class, url, selectedRegistry);
    }

    @Override
    public Sdmx30DataClient getData30Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateDataEndpointConfig(selectedRegistry);
        String url = selectedRegistry.getVersionConfiguration().getDataEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx30DataClient.class, url, selectedRegistry);
    }

    @Override
    public Sdmx21StructureClient getStructure21Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateStructureEndpointConfig(selectedRegistry);
        String url = selectedRegistry.getVersionConfiguration().getStructureEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx21StructureClient.class, url, selectedRegistry);
    }

    @Override
    public Sdmx30StructureClient getStructure30Client(RegistrySelectionResult selectedRegistry) {
        validateSelection(selectedRegistry);
        validateStructureEndpointConfig(selectedRegistry);
        String url = selectedRegistry.getVersionConfiguration().getStructureEndpointConfig().getUrl();
        return getOrBuildClient(Sdmx30StructureClient.class, url, selectedRegistry);
    }

    private void validateSelection(RegistrySelectionResult selectedRegistry) {
        if (selectedRegistry == null || selectedRegistry.getRegistryConfiguration() == null || selectedRegistry.getVersionConfiguration() == null) {
            throw new IllegalRegistryConfigurationException("Registry selection result cannot be null and must contain both registry and version configurations");
        }
    }

    private void validateDataEndpointConfig(RegistrySelectionResult selectedRegistry) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        if (versionConfig.getDataEndpointConfig() == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Data endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }
    }

    private void validateStructureEndpointConfig(RegistrySelectionResult selectedRegistry) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        if (versionConfig.getStructureEndpointConfig() == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Structure endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }
    }

    private void validateAvailabilityEndpointConfig(RegistrySelectionResult selectedRegistry) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        if (versionConfig.getAvailabilityEndpointConfig() == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Availability endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }
    }

    private <T extends SdmxApiBase> T getOrBuildClient(Class<T> clientClass, String baseUrl, RegistrySelectionResult selectedRegistry) {
        String key = buildKey(clientClass, baseUrl, selectedRegistry.getRegistryConfiguration().getName());
        T builtClient = (T) builtApiClients.get(key);

        if (isNull(builtClient)) {
            synchronized (this) {
                // Double-check after acquiring lock
                builtClient = (T) builtApiClients.get(key);
                if (isNull(builtClient)) {
                    builtClient = buildClient(clientClass, baseUrl, selectedRegistry);
                    builtApiClients.put(key, builtClient);
                }
            }
        }

        return builtClient;
    }

    private <T extends SdmxApiBase> T buildClient(Class<T> clientClass, String baseUrl, RegistrySelectionResult selectedRegistry) {
        final String logLevel = System.getenv("FEIGN_LOG_LEVEL");

        FeignDecorators decorators = FeignDecorators.builder()
                .withCircuitBreaker(
                        resilience4JComponentFactory.getOrCreateCircuitBreaker(selectedRegistry, getOperationName(clientClass))
                )
                .withRetry(
                        resilience4JComponentFactory.getOrCreateRetry(selectedRegistry)
                )
                .build();

        var builder = Resilience4jFeign.builder(decorators)
                .client(rateLimitRetryClientProvider.wrap(baseOkHttpClient, selectedRegistry))
                .options(getOptions(selectedRegistry.getVersionConfiguration()))
                .encoder(encoder)
                .decoder(decoder)
                .logger(new Slf4jFeignLogger(clientClass))
                .logLevel(Logger.Level.valueOf(logLevel == null ? "BASIC" : logLevel))
                .doNotCloseAfterDecode();

        addRateLimiting(selectedRegistry, builder);

        return builder.target(clientClass, baseUrl);
    }

    private void addRateLimiting(RegistrySelectionResult selectedRegistry, Feign.Builder builder) {
        if (isRateLimitingEnabled(selectedRegistry.getVersionConfiguration())) {
            InvocationHandlerFactory rateLimitingFactory = new RateLimitingInvocationHandlerFactory(
                    selectedRegistry,
                    resilienceConfig
            );
            builder.invocationHandlerFactory(rateLimitingFactory);
        }
    }

    private boolean isRateLimitingEnabled(VersionSpecificRegistryConfiguration versionConfiguration) {
        RegistryResilienceConfig registryResilienceConfig = versionConfiguration.getResilienceConfig();
        if (registryResilienceConfig != null && registryResilienceConfig.getRateLimit() != null) {
            Boolean enabled = registryResilienceConfig.getRateLimit().getEnabled();
            if (enabled != null) {
                return enabled;
            }
        }

        return resilienceConfig.getDefaultRateLimitingEnabled();
    }

    private Request.Options getOptions(VersionSpecificRegistryConfiguration versionConfiguration) {
        RegistryResilienceConfig registryResilienceConfig = versionConfiguration.getResilienceConfig();
        int connectionTimeout = registryResilienceConfig != null && registryResilienceConfig.getConnectionTimeout() != null
                ? registryResilienceConfig.getConnectionTimeout()
                : resilienceConfig.getDefaultConnectionTimeout();
        int readTimeout = registryResilienceConfig != null && registryResilienceConfig.getReadTimeout() != null
                ? registryResilienceConfig.getReadTimeout()
                : resilienceConfig.getDefaultReadTimeout();

        // Create Request.Options with custom timeouts
        Request.Options options = new Request.Options(connectionTimeout, readTimeout);
        return options;
    }

    private String getOperationName(Class<?> clientClass) {
        return clientClass.getSimpleName().toLowerCase();
    }

    /**
     * Includes the registry name so two registries sharing a base URL do not silently share one
     * another's resilience settings. Note the cache is never evicted, so a configuration reload
     * still has no effect on an already-built client.
     */
    private String buildKey(Class<?> c, String baseUrl, String registryName) {
        return String.format(BUILT_API_CLIENT_KEY_FORMAT, c.getName(), baseUrl, registryName);
    }
}
