package com.epam.sdmxproxy.e2e.tests.framework.config;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@NoArgsConstructor
public class DataTestSuitConfiguration {

    private List<DataflowTestSuitConfiguration> dataflowConfigs;

    private List<String> mediaTypes;

    private List<ReturnFormat> registryReturnFormats;

}
