package com.epam.sdmxproxy.registry.configuration.writer;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StubProxyConfigurationWriter implements ProxyConfigurationWriter {
    @Override
    public void writeConfig(ProxyConfiguration proxyConfiguration) {
        log.warn("Attempted to write configuration, but writing is not supported in current setup");
        throw new UnsupportedOperationException("Config cannot be written in a current setup.");
    }
}
