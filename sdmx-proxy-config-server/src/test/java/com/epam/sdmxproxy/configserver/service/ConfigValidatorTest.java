package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
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
