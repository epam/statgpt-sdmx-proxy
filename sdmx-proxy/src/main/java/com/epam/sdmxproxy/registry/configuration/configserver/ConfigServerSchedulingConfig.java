package com.epam.sdmxproxy.registry.configuration.configserver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
public class ConfigServerSchedulingConfig {
}
