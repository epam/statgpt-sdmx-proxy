package com.epam.sdmxproxy.configserver.writer;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

public interface ConfigWriter {

    void writeConfig(ProxyConfiguration configuration);

    ConfigSourceType sourceType();
}
