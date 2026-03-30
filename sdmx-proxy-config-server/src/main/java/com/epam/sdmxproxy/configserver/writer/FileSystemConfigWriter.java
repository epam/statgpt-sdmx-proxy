package com.epam.sdmxproxy.configserver.writer;

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
public class FileSystemConfigWriter implements ConfigWriter {

    private final ObjectMapper objectMapper;

    @Value("${sdmxproxy.configserver.source.config-path}")
    private String configPath;

    @Override
    @SneakyThrows
    public void writeConfig(ProxyConfiguration configuration) {
        log.info("Writing configuration to file: {}", configPath);
        Path filePath = Path.of(configPath);
        String configJson = objectMapper.writeValueAsString(configuration);
        Files.writeString(filePath, configJson);
        log.info("Successfully wrote configuration to file");
    }

    @Override
    public ConfigSourceType sourceType() {
        return ConfigSourceType.FILESYSTEM;
    }
}
