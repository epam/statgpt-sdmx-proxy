package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configserver.extractor.ConfigExtractor;
import com.epam.sdmxproxy.configserver.writer.ConfigWriter;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private static final String DEFAULT_CONFIG_RESOURCE = "sdmx_registries_config.json";

    private final ConfigExtractor configExtractor;
    private final ConfigWriter configWriter;
    private final ConfigValidator configValidator;
    private final ObjectMapper objectMapper;

    private final AtomicReference<ProxyConfiguration> currentConfig = new AtomicReference<>();
    private final AtomicBoolean storageAvailable = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        try {
            ProxyConfiguration config = configExtractor.getConfiguration();
            if (isNullOrEmpty(config)) {
                log.warn("Configuration from {} is absent or empty", configExtractor.sourceType());
                if (configExtractor.sourceType() == ConfigSourceType.DIAL_STORAGE) {
                    seedFromClasspathDefault();
                }
            } else {
                currentConfig.set(config);
                storageAvailable.set(true);
                log.info("Config server initialized with configuration from {} source", configExtractor.sourceType());
            }
        } catch (Exception e) {
            log.warn("Failed to load configuration from {} on startup: {}. Config server will report unhealthy until storage becomes available.", configExtractor.sourceType(), e.getMessage());
        }
    }

    private boolean isNullOrEmpty(ProxyConfiguration config) {
        if (config == null) {
            return true;
        }
        if (config.getConfigs() == null || config.getConfigs().isEmpty()) {
            return true;
        }
        if (config.getAgencies() == null || config.getAgencies().isEmpty()) {
            return true;
        }
        return false;
    }

    private void seedFromClasspathDefault() {
        log.info("Attempting to seed DIAL Storage from bundled default configuration...");
        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (resourceStream == null) {
                log.error("Default configuration resource not found on classpath: {}", DEFAULT_CONFIG_RESOURCE);
                return;
            }
            byte[] fileBytes = resourceStream.readAllBytes();
            ProxyConfiguration defaultConfig = objectMapper.readValue(fileBytes, ProxyConfiguration.class);
            configValidator.validate(defaultConfig);
            configWriter.writeConfig(defaultConfig);
            currentConfig.set(defaultConfig);
            storageAvailable.set(true);
            log.info("Successfully seeded DIAL Storage with default configuration ({} registries, {} agencies)", defaultConfig.getConfigs().size(), defaultConfig.getAgencies().size());
        } catch (Exception e) {
            log.error("Failed to seed DIAL Storage from default configuration: {}", e.getMessage(), e);
        }
    }

    public ProxyConfiguration getConfiguration() {
        return currentConfig.get();
    }

    public boolean isStorageAvailable() {
        return storageAvailable.get();
    }

    public void updateConfiguration(ProxyConfiguration configuration) {
        configValidator.validate(configuration);
        configWriter.writeConfig(configuration);
        currentConfig.set(configuration);
        storageAvailable.set(true);
        log.info("Configuration updated successfully via {} writer", configWriter.sourceType());
    }
}
