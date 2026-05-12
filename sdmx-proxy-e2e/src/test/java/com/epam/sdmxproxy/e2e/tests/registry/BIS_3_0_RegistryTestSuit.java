package com.epam.sdmxproxy.e2e.tests.registry;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite;
import com.epam.sdmxproxy.e2e.tests.framework.config.RegistryTestSuitConfiguration;
import lombok.SneakyThrows;
import com.fasterxml.jackson.databind.ObjectMapper;


public class BIS_3_0_RegistryTestSuit extends BaseRegistryTestSuite {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @SneakyThrows
    protected ProxyConfiguration getProxyConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("bis/3_0/bis_3_0_registry_config.json").readAllBytes(), ProxyConfiguration.class);
    }

    @Override
    @SneakyThrows
    protected RegistryTestSuitConfiguration getTestConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("bis/3_0/bis_3_0_test_config.json").readAllBytes(), RegistryTestSuitConfiguration.class);
    }
}
