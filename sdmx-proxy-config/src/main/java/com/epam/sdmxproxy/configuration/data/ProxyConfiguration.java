package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.List;

@Data
public class ProxyConfiguration {

    private List<RegistryConfiguration> configs;

    private List<AgencyConfiguration> agencies;

}
