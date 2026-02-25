package com.epam.sdmxproxy.registry.api.config;

import com.epam.sdmxproxy.registry.api.Resilience4jComponentFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Resilience4j components.
 */
@Configuration
public class Resilience4jConfig {

    @Bean
    public Resilience4jComponentFactory resilience4jClientFactory(ResilienceProperties resilienceProperties) {
        return new Resilience4jComponentFactory(resilienceProperties);
    }
}
