package com.epam.sdmxproxy.e2e.tests.framework.config;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AvailabilityTestSuitConfiguration {

    private List<DataflowTestSuitConfiguration> dataflowConfigs;

    private List<String> mediaTypes;

    private List<SdmxFormat> registryReturnFormats;

}
