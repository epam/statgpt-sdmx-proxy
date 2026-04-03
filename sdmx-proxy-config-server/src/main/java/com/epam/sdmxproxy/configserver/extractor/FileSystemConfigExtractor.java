package com.epam.sdmxproxy.configserver.extractor;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = ConfigSourceType.Values.FILESYSTEM)
@RequiredArgsConstructor
public class FileSystemConfigExtractor implements ConfigExtractor {

    private final ObjectMapper objectMapper;

    @Value("${sdmxproxy.configserver.source.config-path}")
    private String configPath;

    @Override
    @SneakyThrows
    public ProxyConfiguration getConfiguration() {
        log.debug("Loading configuration from filesystem: {}", configPath);
        Path filePath = Path.of(configPath);
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("Configuration file not found: " + configPath);
        }
        byte[] fileBytes = Files.readAllBytes(filePath);
        return objectMapper.readValue(fileBytes, ProxyConfiguration.class);
    }

    @Override
    public ConfigSourceType sourceType() {
        return ConfigSourceType.FILESYSTEM;
    }
}
