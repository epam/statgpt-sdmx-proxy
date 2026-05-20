package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.List;

@Data
public class ProxyConfiguration {

    private List<RegistryConfiguration> configs;

    private List<AgencyConfiguration> agencies;

    /**
     * When true, a structure query with {@code agencyId="*"} is fanned out to every
     * configured registry that supports the requested structure type, and results
     * are merged. When false (default), wildcard agency requests return HTTP 501.
     * Comma-separated agency IDs are rejected with 501 in both modes.
     */
    private boolean structureFanOutEnabled;

}
