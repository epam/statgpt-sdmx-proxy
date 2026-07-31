package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Test
    void validConfigPasses() {
        assertDoesNotThrow(() -> validator.validate(validConfig()));
    }

    @Test
    void nullConfigFails() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
    }

    @Test
    void emptyConfigsFails() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of());
        config.setAgencies(List.of(agency("BIS", "BIS")));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
    }

    @Test
    void emptyAgenciesFails() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS")));
        config.setAgencies(List.of());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
    }

    @Test
    void missingPrimaryRegistryFails() {
        AgencyConfiguration agency = new AgencyConfiguration();
        agency.setName("BIS");
        // primaryRegistry not set

        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS")));
        config.setAgencies(List.of(agency));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
        assertTrue(ex.getMessage().contains("primaryRegistry"));
    }

    @Test
    void unknownPrimaryRegistryFails() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS")));
        config.setAgencies(List.of(agency("IMF", "NONEXISTENT")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
        assertTrue(ex.getMessage().contains("NONEXISTENT"));
    }

    @Test
    void duplicateRegistryNamesFails() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS"), registry("BIS")));
        config.setAgencies(List.of(agency("BIS", "BIS")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void registryWithoutVersionsFails() {
        RegistryConfiguration reg = new RegistryConfiguration();
        reg.setName("BIS");
        // versions not set

        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(reg));
        config.setAgencies(List.of(agency("BIS", "BIS")));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(config));
    }

    @Test
    void unwrapStarComponentIdOnSdmx21Fails() {
        // The config server is the creation gate: a config carrying an SDMX 3.0-only workaround on a
        // 2.1 registry must never reach storage, because the proxy would then answer such queries
        // with the whole cube instead of the requested slice.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(configWith(registry21(false, false, true))));
        assertTrue(ex.getMessage().contains("availabilityEndpointConfig.unwrapStarComponentId"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SDMX_2_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("OECD"), ex.getMessage());
    }

    @Test
    void convertKeyToFiltersOnSdmx21DataFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(configWith(registry21(true, false, false))));
        assertTrue(ex.getMessage().contains("dataEndpointConfig.convertKeyToFilters"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no c[] filter parameter"), ex.getMessage());
    }

    @Test
    void convertKeyToFiltersOnSdmx21AvailabilityFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(configWith(registry21(false, true, false))));
        assertTrue(ex.getMessage().contains("availabilityEndpointConfig.convertKeyToFilters"), ex.getMessage());
    }

    @Test
    void sameFlagsOnSdmx30Pass() {
        RegistryConfiguration registry = registry21(true, true, true);
        registry.getVersions().values().iterator().next().setSdmxVersion(SdmxVersion.SDMX_3_0);
        registry.setVersions(Map.of(SdmxVersion.SDMX_3_0, registry.getVersions().values().iterator().next()));

        assertDoesNotThrow(() -> validator.validate(configWith(registry)));
    }

    @Test
    void sdmx21DeclaredOnlyByTheNestedFieldStillFails() {
        // The version is declared twice -- map key and nested field. A 2.1 marker in either place is
        // enough for the flag to reach the proxy's 2.1 code path.
        RegistryConfiguration registry = registry21(true, false, false);
        VersionSpecificRegistryConfiguration version = registry.getVersions().values().iterator().next();
        registry.setVersions(Map.of(SdmxVersion.SDMX_3_0, version));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(configWith(registry)));
    }

    @Test
    void sdmx21DeclaredOnlyByTheMapKeyStillFails() {
        RegistryConfiguration registry = registry21(true, false, false);
        registry.getVersions().values().iterator().next().setSdmxVersion(null);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(configWith(registry)));
    }

    @Test
    void sdmx21RegistryWithoutEndpointConfigsPasses() {
        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_2_1);
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("OECD");
        registry.setVersions(Map.of(SdmxVersion.SDMX_2_1, version));

        assertDoesNotThrow(() -> validator.validate(configWith(registry)));
    }

    private ProxyConfiguration configWith(RegistryConfiguration registry) {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry));
        config.setAgencies(List.of(agency(registry.getName(), registry.getName())));
        return config;
    }

    private RegistryConfiguration registry21(boolean dataConvertKeyToFilters, boolean availabilityConvertKeyToFilters, boolean unwrapStarComponentId) {
        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setConvertKeyToFilters(dataConvertKeyToFilters);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setConvertKeyToFilters(availabilityConvertKeyToFilters);
        availabilityConfig.setUnwrapStarComponentId(unwrapStarComponentId);

        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_2_1);
        version.setDataEndpointConfig(dataConfig);
        version.setAvailabilityEndpointConfig(availabilityConfig);

        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("OECD");
        registry.setVersions(Map.of(SdmxVersion.SDMX_2_1, version));
        return registry;
    }

    private ProxyConfiguration validConfig() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS"), registry("IMF")));
        config.setAgencies(List.of(agency("BIS", "BIS"), agency("IMF", "IMF")));
        return config;
    }

    private RegistryConfiguration registry(String name) {
        RegistryConfiguration reg = new RegistryConfiguration();
        reg.setName(name);
        VersionSpecificRegistryConfiguration v = new VersionSpecificRegistryConfiguration();
        v.setSdmxVersion(SdmxVersion.SDMX_3_0);
        reg.setVersions(Map.of(SdmxVersion.SDMX_3_0, v));
        return reg;
    }

    private AgencyConfiguration agency(String name, String primaryRegistry) {
        AgencyConfiguration agency = new AgencyConfiguration();
        agency.setName(name);
        agency.setPrimaryRegistry(primaryRegistry);
        return agency;
    }
}
