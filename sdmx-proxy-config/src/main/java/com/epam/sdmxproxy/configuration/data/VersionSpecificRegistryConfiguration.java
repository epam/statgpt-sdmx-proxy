package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

/**
 * Version-specific configuration for an SDMX registry.
 * Contains all settings that differ between SDMX versions (2.1, 3.0, etc.)
 */
@Data
public class VersionSpecificRegistryConfiguration {

    private SdmxVersion sdmxVersion;

    private StructureEndpointConfiguration structureEndpointConfig;

    private DataEndpointConfiguration dataEndpointConfig;

    private AvailabilityEndpointConfiguration availabilityEndpointConfig;

    private RegistryResilienceConfig resilienceConfig;

}
