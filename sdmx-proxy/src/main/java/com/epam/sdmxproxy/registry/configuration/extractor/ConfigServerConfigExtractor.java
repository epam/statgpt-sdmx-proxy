package com.epam.sdmxproxy.registry.configuration.extractor;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.epam.sdmxproxy.registry.configuration.configserver.ConfigServerFeignApi;
import com.epam.sdmxproxy.registry.configuration.configserver.ConfigServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
@RequiredArgsConstructor
public class ConfigServerConfigExtractor implements ProxyConfigurationExtractor {

    private static final String RESOURCE_FILE_NAME = "sdmx_registries_config.json";

    private final ConfigServerFeignApi configServerFeignApi;
    private final ConfigServerProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<ProxyConfiguration> cachedConfig = new AtomicReference<>();

    @PostConstruct
    void init() {
        // Pre-populate with classpath config as baseline -- ensures getConfiguration() never returns null
        cachedConfig.set(loadClasspathConfig());
        log.info("ConfigServerConfigExtractor initialized with classpath config as baseline");
    }

    @Override
    public ProxyConfiguration getConfiguration() {
        return cachedConfig.get();
    }

    @Override
    public ProxyConfigurationSourceType supports() {
        return ProxyConfigurationSourceType.CONFIG_SERVER;
    }

    public void pollForUpdate() {
        try {
            ProxyConfiguration config = configServerFeignApi.getConfig(properties.getApiKey());
            if (config == null) {
                log.warn("Config server returned null configuration. Ignoring.");
                return;
            }
            if (config.getConfigs() == null || config.getConfigs().isEmpty() || config.getAgencies() == null || config.getAgencies().isEmpty()) {
                log.warn("Config server returned invalid configuration (empty configs or agencies). Ignoring.");
                return;
            }
            cachedConfig.set(config);
            log.debug("Successfully fetched configuration from config server");
        } catch (Exception e) {
            log.warn("Failed to fetch configuration from config server: {}. Continuing with last known good config.", e.getMessage());
        }
    }

    private ProxyConfiguration loadClasspathConfig() {
        try (var resourceStream = this.getClass().getClassLoader().getResourceAsStream(RESOURCE_FILE_NAME)) {
            if (resourceStream == null) {
                log.error("Classpath config resource not found: {}", RESOURCE_FILE_NAME);
                throw new IllegalStateException("Configuration resource not found: " + RESOURCE_FILE_NAME);
            }
            byte[] fileBytes = resourceStream.readAllBytes();
            return objectMapper.readValue(fileBytes, ProxyConfiguration.class);
        } catch (Exception e) {
            log.error("Failed to load classpath config: {}", e.getMessage());
            throw new IllegalStateException("Failed to load classpath configuration", e);
        }
    }
}
