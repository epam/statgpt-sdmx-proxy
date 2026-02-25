package com.epam.sdmxproxy.configuration.data;

import lombok.Builder;
import lombok.Data;

/**
 * Result of registry and version selection.
 * Contains both registry-level configuration and selected version-specific configuration.
 */
@Data
@Builder
public class RegistrySelectionResult {
    /**
     * Registry-level configuration (name, description, supported agencies).
     */
    private RegistryConfiguration registryConfiguration;

    /**
     * Version-specific configuration for the selected SDMX version.
     */
    private VersionSpecificRegistryConfiguration versionConfiguration;
}
