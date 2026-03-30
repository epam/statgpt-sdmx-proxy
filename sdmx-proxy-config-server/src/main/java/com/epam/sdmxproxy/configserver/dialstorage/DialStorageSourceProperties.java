package com.epam.sdmxproxy.configserver.dialstorage;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = ConfigSourceType.Values.DIAL_STORAGE)
@ConfigurationProperties(prefix = "sdmxproxy.configserver.dial-storage")
public class DialStorageSourceProperties {

    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 15_000;
    private int writeTimeoutMs = 30_000;
}
