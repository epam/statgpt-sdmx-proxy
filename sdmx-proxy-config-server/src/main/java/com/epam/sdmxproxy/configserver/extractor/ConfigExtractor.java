package com.epam.sdmxproxy.configserver.extractor;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

public interface ConfigExtractor {

    ProxyConfiguration getConfiguration();

    ConfigSourceType sourceType();
}
