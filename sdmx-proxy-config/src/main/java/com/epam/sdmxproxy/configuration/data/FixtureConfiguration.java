package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.Map;

@Data
public class FixtureConfiguration {
    private FixtureType type;
    private Map<String, String> config;
}
