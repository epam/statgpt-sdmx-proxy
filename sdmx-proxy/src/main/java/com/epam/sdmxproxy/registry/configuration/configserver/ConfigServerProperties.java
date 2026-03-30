package com.epam.sdmxproxy.registry.configuration.configserver;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
@ConfigurationProperties(prefix = "sdmxproxy.registry.config.source.config-server")
public class ConfigServerProperties {

    private String url;
    private String apiKey;
    private String pollInterval = "PT30S";
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 15_000;
}
