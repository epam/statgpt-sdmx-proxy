package com.epam.sdmxproxy.e2e.tests.framework.config;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class StructuresTestSuitConfiguration {
    private List<StructureTypeAndUrn> artefacts;

    private List<String> mediaTypes;

    private List<ReturnFormat> registryReturnFormats;
}
