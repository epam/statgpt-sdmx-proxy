package com.epam.sdmxproxy.configserver.controller;

import com.epam.sdmxproxy.configserver.api.ConfigServerApi;
import com.epam.sdmxproxy.configserver.service.ConfigService;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ConfigServerController implements ConfigServerApi {

    private final ConfigService configService;

    @Override
    public ResponseEntity<ProxyConfiguration> getConfig() {
        log.debug("GET /statgpt/sdmx-proxy/api/v0/config");
        if (!configService.isStorageAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        ProxyConfiguration config = configService.getConfiguration();
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @Override
    public ResponseEntity<ProxyConfiguration> updateConfig(ProxyConfiguration configuration) {
        log.info("POST /statgpt/sdmx-proxy/api/v0/config - updating configuration");
        configService.updateConfiguration(configuration);
        return ResponseEntity.ok(configuration);
    }
}
