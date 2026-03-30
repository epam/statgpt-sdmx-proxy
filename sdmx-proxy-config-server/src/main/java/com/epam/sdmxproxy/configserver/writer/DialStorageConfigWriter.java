package com.epam.sdmxproxy.configserver.writer;

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
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = "DIAL_STORAGE")
@RequiredArgsConstructor
public class DialStorageConfigWriter implements ConfigWriter {

    private final DialStorageClient dialStorageClient;
    private final ObjectMapper objectMapper;

    @Value("${sdmxproxy.configserver.source.config-path:config/sdmx_registries_config.json}")
    private String configPath;

    @Override
    public void writeConfig(ProxyConfiguration configuration) {
        try {
            byte[] content = objectMapper.writeValueAsBytes(configuration);
            String bucketName = dialStorageClient.getBucketName();
            dialStorageClient.putObjectContent(bucketName, configPath, content);
            log.info("Successfully wrote configuration to DIAL Storage");
        } catch (Exception e) {
            log.error("Failed to write configuration to DIAL Storage: {}", e.getMessage());
            throw new IllegalStateException("Failed to write configuration to DIAL Storage", e);
        }
    }

    @Override
    public ConfigSourceType sourceType() {
        return ConfigSourceType.DIAL_STORAGE;
    }
}
