package com.epam.sdmxproxy.registry.configuration.extractor;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnvProxyConfigExtractor implements ProxyConfigurationExtractor {

    public static final String ENV_PROXY_CONFIG_SOURCE = "ENV_PROXY_CONFIG_SOURCE";
    private final Environment env;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public ProxyConfiguration getConfiguration() {
        log.debug("Loading configuration from environment variable: {}", ENV_PROXY_CONFIG_SOURCE);
        String rawConfig = env.getProperty(ENV_PROXY_CONFIG_SOURCE);
        if (rawConfig == null || rawConfig.trim().isEmpty()) {
            log.error("Environment variable {} is not set or is empty", ENV_PROXY_CONFIG_SOURCE);
            throw new IllegalStateException("Environment variable " + ENV_PROXY_CONFIG_SOURCE + " is not set");
        }
        log.debug("Read configuration from environment variable");
        return objectMapper.readValue(rawConfig, ProxyConfiguration.class);
    }

    @Override
    public ProxyConfigurationSourceType supports() {
        return ProxyConfigurationSourceType.ENV;
    }
}
