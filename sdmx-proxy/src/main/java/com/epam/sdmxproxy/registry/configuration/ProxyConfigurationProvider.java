package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

public interface ProxyConfigurationProvider {

    ProxyConfiguration getConfiguration();

}
