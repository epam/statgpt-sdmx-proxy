package com.epam.sdmxproxy.registry.configuration.writer;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@Primary
@ConditionalOnProperty(
        name = "sdmxproxy.registry.config.source.type",
        havingValue = "FILESYSTEM"
)
@RequiredArgsConstructor
public class FileSystemProxyConfigurationWriter implements ProxyConfigurationWriter {

    private final ObjectMapper objectMapper;
    @Value("${sdmxproxy.registry.config.source.filename}")
    private String fileName;

    @Override
    @SneakyThrows
    public void writeConfig(ProxyConfiguration proxyConfiguration) {
        log.info("Writing configuration to file: {}", fileName);
        Path configPath = Path.of(fileName);
        String configJson = objectMapper.writeValueAsString(proxyConfiguration);
        Files.writeString(configPath, configJson);
        log.info("Successfully wrote configuration to file");
    }
}
