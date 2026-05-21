package com.epam.sdmxproxy.registry.configuration.extractor;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.epam.sdmxproxy.exception.ConfigurationLoadException;
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
@ConditionalOnProperty(
        name = "sdmxproxy.registry.config.source.type",
        havingValue = "FILESYSTEM"
)
@RequiredArgsConstructor
public class FileSystemConfigExtractorProxy implements ProxyConfigurationExtractor {

    private final ObjectMapper objectMapper;
    @Value("${sdmxproxy.registry.config.source.filename}")
    private String fileName;

    @Override
    @SneakyThrows
    public ProxyConfiguration getConfiguration() {
        log.debug("Loading configuration from filesystem: {}", fileName);
        Path configPath = Path.of(fileName);
        if (!Files.exists(configPath)) {
            log.error("Configuration file does not exist: {}", fileName);
            throw new ConfigurationLoadException("Configuration file not found: " + fileName);
        }
        byte[] fileBytes = Files.readAllBytes(configPath);

        log.debug("Successfully loaded configuration from filesystem");
        return objectMapper.readValue(fileBytes, ProxyConfiguration.class);
    }

    @Override
    public ProxyConfigurationSourceType supports() {
        return ProxyConfigurationSourceType.FILESYSTEM;
    }
}
