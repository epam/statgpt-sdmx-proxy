package com.epam.sdmxproxy.registry.configuration.configserver;

import com.epam.sdmxproxy.registry.api.config.Slf4jFeignLogger;
import feign.Client;
import feign.Feign;
import feign.Logger;
import feign.Request;
import feign.jackson.JacksonDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
@RequiredArgsConstructor
public class ConfigServerFeignConfig {

    private final ConfigServerProperties properties;

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Bean
    public ConfigServerFeignApi configServerFeignApi(Client client) {
        final String logLevel = System.getenv("FEIGN_LOG_LEVEL");

        String baseUrl = normalizeBaseUrl(properties.getUrl());

        Request.Options options = new Request.Options(properties.getConnectTimeoutMs(), properties.getReadTimeoutMs(), true);

        return Feign.builder()
                .client(client)
                .decoder(new JacksonDecoder())
                .options(options)
                .logger(new Slf4jFeignLogger(ConfigServerFeignApi.class))
                .logLevel(Logger.Level.valueOf(logLevel == null ? "BASIC" : logLevel))
                .target(ConfigServerFeignApi.class, baseUrl);
    }
}
