package com.epam.sdmxproxy.configserver.extractor;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configserver.dialstorage.DialStorageClient;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = ConfigSourceType.Values.DIAL_STORAGE)
@RequiredArgsConstructor
public class DialStorageConfigExtractor implements ConfigExtractor {

    private final DialStorageClient dialStorageClient;
    private final ObjectMapper objectMapper;

    @Value("${sdmxproxy.configserver.source.config-path:config/sdmx_registries_config.json}")
    private String configPath;

    @Override
    public ProxyConfiguration getConfiguration() {
        log.debug("Loading configuration from DIAL Storage: {}", configPath);
        String bucketName = dialStorageClient.getBucketName();
        byte[] content = dialStorageClient.getObjectContent(bucketName, configPath);
        if (content == null) {
            log.info("Configuration not found in DIAL Storage at path: {}", configPath);
            return null;
        }
        try {
            return objectMapper.readValue(content, ProxyConfiguration.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse configuration from DIAL Storage", e);
        }
    }

    @Override
    public ConfigSourceType sourceType() {
        return ConfigSourceType.DIAL_STORAGE;
    }
}
