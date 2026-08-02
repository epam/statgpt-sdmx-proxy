package com.epam.sdmxproxy.e2e.tests.registry;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite;
import com.epam.sdmxproxy.e2e.tests.framework.config.RegistryTestSuitConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;


public class OECD_2_1_RegistryTestSuit extends BaseRegistryTestSuite {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @SneakyThrows
    protected ProxyConfiguration getProxyConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("oecd/2_1/oecd_2_1_registry_config.json").readAllBytes(), ProxyConfiguration.class);
    }

    @Override
    @SneakyThrows
    protected RegistryTestSuitConfiguration getTestConfig() {
        return objectMapper.readValue(this.getClass().getResourceAsStream("oecd/2_1/oecd_2_1_test_config.json").readAllBytes(), RegistryTestSuitConfiguration.class);
    }
}
