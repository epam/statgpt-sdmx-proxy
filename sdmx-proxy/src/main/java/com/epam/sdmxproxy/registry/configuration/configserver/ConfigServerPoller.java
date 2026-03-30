package com.epam.sdmxproxy.registry.configuration.configserver;

import com.epam.sdmxproxy.registry.configuration.extractor.ConfigServerConfigExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
@RequiredArgsConstructor
public class ConfigServerPoller {

    private final ConfigServerConfigExtractor extractor;

    @Scheduled(fixedDelayString = "${sdmxproxy.registry.config.source.config-server.poll-interval:PT30S}")
    public void poll() {
        log.trace("Polling config server for configuration updates");
        extractor.pollForUpdate();
    }
}
