package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configserver.extractor.ConfigExtractor;
import com.epam.sdmxproxy.configserver.writer.ConfigWriter;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigExtractor configExtractor;
    private final ConfigWriter configWriter;
    private final ConfigValidator configValidator;

    private final AtomicReference<ProxyConfiguration> currentConfig = new AtomicReference<>();
    private final AtomicBoolean storageAvailable = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        try {
            ProxyConfiguration config = configExtractor.getConfiguration();
            currentConfig.set(config);
            storageAvailable.set(true);
            log.info("Config server initialized with configuration from {} source", configExtractor.sourceType());
        } catch (Exception e) {
            log.warn("Failed to load configuration from {} on startup: {}. Config server will report unhealthy until storage becomes available.", configExtractor.sourceType(), e.getMessage());
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
