package com.epam.sdmxproxy.configserver.dialstorage;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import feign.Client;
import feign.Feign;
import feign.Logger;
import feign.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = ConfigSourceType.Values.DIAL_STORAGE)
@RequiredArgsConstructor
public class DialStorageFeignConfig {

    private final DialStorageSourceProperties properties;

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Bean
    public DialStorageFeignDecoder dialStorageFeignDecoder() {
        return new DialStorageFeignDecoder();
    }

    @Bean
    public DialStorageMultipartEncoder dialStorageMultipartEncoder() {
        return new DialStorageMultipartEncoder();
    }

    @Bean
    public DialStorageFeignApi dialStorageFeignApi(Client client, DialStorageFeignDecoder dialStorageFeignDecoder, DialStorageMultipartEncoder dialStorageMultipartEncoder) {
        final String logLevel = System.getenv("FEIGN_LOG_LEVEL");

        String baseUrl = normalizeBaseUrl(properties.getBaseUrl());

        Request.Options options = new Request.Options(
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs(),
                true
        );

        return Feign.builder()
                .client(client)
                .decoder(dialStorageFeignDecoder)
                .encoder(dialStorageMultipartEncoder)
                .options(options)
                .logger(new feign.slf4j.Slf4jLogger(DialStorageFeignApi.class))
                .logLevel(Logger.Level.valueOf(logLevel == null ? "BASIC" : logLevel))
                .target(DialStorageFeignApi.class, baseUrl);
    }

    @Bean
    public Client feignClient() {
        return new Client.Default(null, null);
    }
}
