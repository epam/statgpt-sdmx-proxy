package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RegistryTestSuitConfiguration {

    private StructuresTestSuitConfiguration structuresTestSuitConfiguration;

    private DataTestSuitConfiguration dataTestSuitConfiguration;

    private AvailabilityTestSuitConfiguration availabilityTestSuitConfiguration;

    /**
     * Optional. When present, enables generic {@code limit}-related diagnostics in
     * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}.
     */
    private LimitTestSuitConfiguration limitTestSuitConfiguration;
}
