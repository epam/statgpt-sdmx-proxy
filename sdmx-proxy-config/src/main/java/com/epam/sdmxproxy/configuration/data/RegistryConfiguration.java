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
     * Which adapter talks to this registry. Null or {@code SDMX} is a standard SDMX registry;
     * {@code STATCAN} is Statistics Canada -- see {@link RegistryAdapterType}.
     */
    private RegistryAdapterType adapter = RegistryAdapterType.SDMX;

    /**
     * Free-form settings of a non-SDMX {@link #adapter}, in the shape of
     * {@code FixtureConfiguration.config}. {@code STATCAN} reads {@code catalogUrl} (required: the
     * WDS base URL, {@code .../t1/wds/rest/}) and {@code includeArchived} ({@code "true"} lists
     * archived tables in the dataflow catalog; default {@code "false"}).
     */
    private Map<String, String> adapterConfig;

    /**
     * Helper method to get version configuration by SDMX version.
     * Returns null if version not found.
     */
    public VersionSpecificRegistryConfiguration getVersionConfiguration(SdmxVersion sdmxVersion) {
        return versions != null ? versions.get(sdmxVersion) : null;
    }

}
