package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataflowKeyCase {
    private String urn;
    private String key;
}
