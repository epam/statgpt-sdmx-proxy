package com.epam.sdmxproxy.registry.configuration.extractor;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.epam.sdmxproxy.exception.ConfigurationLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClasspathResourceConfigExtractorProxy implements ProxyConfigurationExtractor {
    private static final String RESOURCE_FILE_NAME = "sdmx_registries_config.json";
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public ProxyConfiguration getConfiguration() {
        log.debug("Loading configuration from classpath resource: {}", RESOURCE_FILE_NAME);

        try {
            var resourceStream = this.getClass().getClassLoader().getResourceAsStream(RESOURCE_FILE_NAME);
            if (resourceStream == null) {
                log.error("Configuration resource not found in classpath: {}", RESOURCE_FILE_NAME);
                throw new ConfigurationLoadException("Configuration resource not found: " + RESOURCE_FILE_NAME);
            }

            byte[] fileBytes = resourceStream.readAllBytes();
            log.debug("Read {} bytes from classpath resource: {}", fileBytes.length, RESOURCE_FILE_NAME);

            ProxyConfiguration configuration = objectMapper.readValue(fileBytes, ProxyConfiguration.class);
            log.info("Successfully loaded configuration from classpath resource: {} ({} registry config(s))",
                    RESOURCE_FILE_NAME,
                    configuration.getConfigs() != null ? configuration.getConfigs().size() : 0);
            return configuration;
        } catch (Exception e) {
            log.error("Failed to load configuration from classpath resource: {}", RESOURCE_FILE_NAME, e);
            throw e;
        }
    }

    @Override
    public ProxyConfigurationSourceType supports() {
        return ProxyConfigurationSourceType.CLASSPATH_RESOURCE;
    }
}
