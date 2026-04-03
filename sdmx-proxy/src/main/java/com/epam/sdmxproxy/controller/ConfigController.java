package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.ConfigApi;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProviderImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration controller.
 * GET is always available for debugging.
 * POST is only available when sdmxproxy.test.config-endpoint.enabled=true (for E2E tests).
 * In production, configuration is managed via the config server.
 */
@Slf4j
@RestController
public class ConfigController implements ConfigApi {

    private final ProxyConfigurationProviderImpl proxyConfigurationProvider;
    private final boolean testEndpointEnabled;

    public ConfigController(ProxyConfigurationProviderImpl proxyConfigurationProvider, @Value("${sdmxproxy.test.config-endpoint.enabled:false}") boolean testEndpointEnabled) {
        this.proxyConfigurationProvider = proxyConfigurationProvider;
        this.testEndpointEnabled = testEndpointEnabled;
    }

    @Override
    public ResponseEntity<ProxyConfiguration> getConfig() {
        log.info("Received GET request to retrieve configuration");
        return ResponseEntity.ok(proxyConfigurationProvider.getConfiguration());
    }

    @Override
    public ResponseEntity<?> updateConfig(@RequestBody ProxyConfiguration proxyConfiguration) {
        if (!testEndpointEnabled) {
            log.warn("POST /config is disabled. Use the config server to update configuration.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("message", "POST /config is disabled. Use the config server to update configuration.", "status", 404));
        }
        log.info("Received POST request to update configuration (test mode)");
        proxyConfigurationProvider.setRuntimeOverride(proxyConfiguration);
        log.info("Successfully updated configuration via runtime override");
        return ResponseEntity.ok().build();
    }
}
