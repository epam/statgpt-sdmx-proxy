package com.epam.sdmxproxy.registry.configuration.writer;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

public interface ProxyConfigurationWriter {
    void writeConfig(ProxyConfiguration proxyConfiguration);
}
