package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ConfigValidator {

    private static final String DROPPED_NARROWING_REASON = "SDMX 2.1 REST has no c[] filter parameter, so every dimension narrowing moved out of the path key would be dropped silently and the registry would return the whole cube.";

    private static final String COMMA_COMPONENT_ID_REASON = "SDMX 2.1 availability accepts a single componentId or 'all', not the comma-joined dimension list this flag produces.";

    public void validate(ProxyConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        List<RegistryConfiguration> configs = configuration.getConfigs();
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("At least one registry must be defined in 'configs'");
        }

        // Check for duplicate registry names
        Set<String> registryNames = new HashSet<>();
        for (RegistryConfiguration registry : configs) {
            if (registry.getName() == null || registry.getName().isBlank()) {
                throw new IllegalArgumentException("Registry name must not be null or blank");
            }
            if (!registryNames.add(registry.getName())) {
                throw new IllegalArgumentException("Duplicate registry name: " + registry.getName());
            }
            if (registry.getVersions() == null || registry.getVersions().isEmpty()) {
                throw new IllegalArgumentException("Registry '" + registry.getName() + "' must have at least one version configured");
            }
        }

        List<AgencyConfiguration> agencies = configuration.getAgencies();
        if (agencies == null || agencies.isEmpty()) {
            throw new IllegalArgumentException("At least one agency must be defined in 'agencies'");
        }

        Set<String> validRegistryNames = configs.stream().map(RegistryConfiguration::getName).collect(Collectors.toSet());

        for (AgencyConfiguration agency : agencies) {
            if (agency.getName() == null || agency.getName().isBlank()) {
                throw new IllegalArgumentException("Agency name must not be null or blank");
            }
            if (agency.getPrimaryRegistry() == null || agency.getPrimaryRegistry().isBlank()) {
                throw new IllegalArgumentException("Agency '" + agency.getName() + "' must have a primaryRegistry");
            }
            if (!validRegistryNames.contains(agency.getPrimaryRegistry())) {
                throw new IllegalArgumentException("Agency '" + agency.getName() + "' references unknown registry '" + agency.getPrimaryRegistry() + "'. Available registries: " + validRegistryNames);
            }
        }

        for (RegistryConfiguration registry : configs) {
            registry.getVersions().forEach((version, versionConfig) -> validateVersionCompatibility(registry.getName(), version, versionConfig));
        }
    }

    /**
     * Rejects endpoint flags that cannot work on the SDMX version their registry declares.
     *
     * <p>Both flags checked here are workarounds for specific SDMX 3.0 registries and have no SDMX
     * 2.1 equivalent. Left enabled on a 2.1 registry they do not merely no-op:
     *
     * <ul>
     *   <li>{@code convertKeyToFilters} moves every dimension narrowing out of the path key into
     *       {@code c[]} filters, but the proxy's SDMX 2.1 data and availability clients carry no
     *       filter parameters at all -- the narrowing is dropped and the registry answers with the
     *       whole cube. HTTP 200, wrong data, no warning.</li>
     *   <li>{@code unwrapStarComponentId} rewrites the component ID into a comma-joined dimension
     *       list, which the SDMX 2.1 availability grammar rejects.</li>
     * </ul>
     */
    private void validateVersionCompatibility(String registryName, SdmxVersion declaredVersion, VersionSpecificRegistryConfiguration versionConfig) {
        if (versionConfig == null || !isSdmx21(declaredVersion, versionConfig)) {
            return;
        }

        DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
        if (dataConfig != null && dataConfig.isConvertKeyToFilters()) {
            throw unsupportedFlag(registryName, "dataEndpointConfig.convertKeyToFilters", DROPPED_NARROWING_REASON);
        }

        AvailabilityEndpointConfiguration availabilityConfig = versionConfig.getAvailabilityEndpointConfig();
        if (availabilityConfig == null) {
            return;
        }
        if (availabilityConfig.isConvertKeyToFilters()) {
            throw unsupportedFlag(registryName, "availabilityEndpointConfig.convertKeyToFilters", DROPPED_NARROWING_REASON);
        }
        if (availabilityConfig.isUnwrapStarComponentId()) {
            throw unsupportedFlag(registryName, "availabilityEndpointConfig.unwrapStarComponentId", COMMA_COMPONENT_ID_REASON);
        }
    }

    /**
     * The version is declared twice -- as the {@code versions} map key and as the nested
     * {@code sdmxVersion} field. Either one reaching the proxy is enough to do the damage, so a 2.1
     * marker in either place trips the check.
     */
    private boolean isSdmx21(SdmxVersion declaredVersion, VersionSpecificRegistryConfiguration versionConfig) {
        return declaredVersion == SdmxVersion.SDMX_2_1 || versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1;
    }

    private IllegalArgumentException unsupportedFlag(String registryName, String flag, String reason) {
        return new IllegalArgumentException(String.format("Registry '%s' enables %s on an SDMX_2_1 version. %s Remove the flag or declare the version as SDMX_3_0.", registryName, flag, reason));
    }
}
