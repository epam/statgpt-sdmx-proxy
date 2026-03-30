package com.epam.sdmxproxy.configuration.data.fixture;

import lombok.Data;

import java.util.Map;

@Data
public class FixtureConfiguration<T extends FixtureType> {
    private T type;
    private Map<String, String> config;
}
