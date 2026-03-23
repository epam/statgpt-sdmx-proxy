package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class DataflowTestSuitConfiguration {
    private String urn;
    private List<String> keys;
    private Map<String, String> filters;

}
