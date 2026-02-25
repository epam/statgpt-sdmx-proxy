package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.ConfigApi;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.registry.configuration.writer.ProxyConfigurationWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/*
TODO
This approach will not work in scalable environment.
Need to implement something similar to QH config server.
It will require this app to have creds to update K8S config maps.
Or there is another way - using some file share as a storage for config source.
 */

@Slf4j
@RestController
@RequiredArgsConstructor
public class ConfigController implements ConfigApi {

    private final ProxyConfigurationProvider proxyConfigurationProvider;
    private final ProxyConfigurationWriter proxyConfigurationWriter;

    @Override
    public ResponseEntity<ProxyConfiguration> getConfig() {
        log.info("Received GET request to retrieve configuration");
        return ResponseEntity.ok(proxyConfigurationProvider.getConfiguration());
    }

    @Override
    public ResponseEntity<?> updateConfig(@RequestBody ProxyConfiguration proxyConfiguration) {
        log.info("Received POST request to update configuration");
        proxyConfigurationWriter.writeConfig(proxyConfiguration);
        log.info("Successfully updated configuration");
        return ResponseEntity.ok().build();
    }
}
