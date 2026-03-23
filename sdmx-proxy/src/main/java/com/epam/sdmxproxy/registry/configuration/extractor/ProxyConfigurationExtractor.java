package com.epam.sdmxproxy.registry.configuration.extractor;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;

public interface ProxyConfigurationExtractor {

    ProxyConfiguration getConfiguration();

    ProxyConfigurationSourceType supports();

}
