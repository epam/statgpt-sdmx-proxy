package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configserver.extractor.ConfigExtractor;
import com.epam.sdmxproxy.configserver.writer.ConfigWriter;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    @Value("${sdmxproxy.configserver.source.force-seed:false}")
    private boolean forceSeed;

    @PostConstruct
    void init() {
        if (forceSeed) {
            if (configExtractor.sourceType() != ConfigSourceType.DIAL_STORAGE) {
                throw new IllegalStateException("CONFIG_SERVER_FORCE_SEED=true is only supported with CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE, got " + configExtractor.sourceType());
            }
            log.warn("CONFIG_SERVER_FORCE_SEED=true — overwriting stored configuration with bundled classpath default. Flip this flag back to false after a healthy rollout.");
            forceSeedFromClasspathDefault();
            return;
        }

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
            ProxyConfiguration defaultConfig = writeConfigFromStream(resourceStream);
            log.info("Successfully seeded DIAL Storage with default configuration ({} registries, {} agencies)", defaultConfig.getConfigs().size(), defaultConfig.getAgencies().size());
        } catch (Exception e) {
            log.error("Failed to seed DIAL Storage from default configuration: {}", e.getMessage(), e);
        }
    }

    private void forceSeedFromClasspathDefault() {
        log.info("Forced reseed: loading bundled default configuration from classpath resource {}", DEFAULT_CONFIG_RESOURCE);
        try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (resourceStream == null) {
                throw new IllegalStateException("Default configuration resource not found on classpath: " + DEFAULT_CONFIG_RESOURCE);
            }
            ProxyConfiguration defaultConfig = writeConfigFromStream(resourceStream);
            log.info("Forced reseed complete: overwrote DIAL Storage with bundled default ({} registries, {} agencies). Remember to unset CONFIG_SERVER_FORCE_SEED before the next restart or you will wipe manual changes again.", defaultConfig.getConfigs().size(), defaultConfig.getAgencies().size());
        } catch (IOException e) {
            throw new IllegalStateException("Forced reseed failed: " + e.getMessage(), e);
        }
    }

    private ProxyConfiguration writeConfigFromStream(InputStream resourceStream) throws IOException {
        byte[] fileBytes = resourceStream.readAllBytes();
        ProxyConfiguration defaultConfig = objectMapper.readValue(fileBytes, ProxyConfiguration.class);
        configValidator.validate(defaultConfig);
        configWriter.writeConfig(defaultConfig);
        currentConfig.set(defaultConfig);
        storageAvailable.set(true);
        return defaultConfig;
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
