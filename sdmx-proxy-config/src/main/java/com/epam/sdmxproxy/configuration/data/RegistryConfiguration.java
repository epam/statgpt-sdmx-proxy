package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.Map;

/**
 * Registry-level configuration containing registry metadata and version-specific configurations.
 * Each registry can support multiple SDMX versions, each with its own configuration.
 */
@Data
public class RegistryConfiguration {
    /**
     * Registry name.
     */
    private String name;

    /**
     * Human-readable description of the registry.
     */
    private String description;

    /**
     * Map of SDMX version to version-specific configuration.
     * Allows one registry to support multiple SDMX versions (e.g., 2.1 and 3.0)
     * with different URLs, formats, and resilience settings per version.
     * <p>
     * In JSON, this is represented as an object with keys matching enum names (e.g., "SDMX_2_1", "SDMX_3_0").
     */
    private Map<SdmxVersion, VersionSpecificRegistryConfiguration> versions;

    /**
     * Helper method to get version configuration by SDMX version.
     * Returns null if version not found.
     */
    public VersionSpecificRegistryConfiguration getVersionConfiguration(SdmxVersion sdmxVersion) {
        return versions != null ? versions.get(sdmxVersion) : null;
    }

}
