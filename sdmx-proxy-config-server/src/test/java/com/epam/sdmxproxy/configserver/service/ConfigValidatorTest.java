package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void acceptsTheShippedBaselineConfiguration() throws Exception {
        // The config the proxy seeds itself from must pass the validator that guards config
        // pushes. Without this, a baseline registry could ship a combination the config server
        // would reject -- discovered only when someone tried to push it back.
        try (InputStream shipped = ConfigValidatorTest.class.getResourceAsStream("/sdmx_registries_config.json")) {
            assertNotNull(shipped, "sdmx_registries_config.json must be on the classpath");
            ProxyConfiguration configuration = new ObjectMapper().readValue(shipped, ProxyConfiguration.class);

            assertDoesNotThrow(() -> validator.validate(configuration));

            // Also pins that the emulation block binds rather than being silently ignored: ESTAT
            // is the only baseline registry whose availability endpoint does not work.
            AvailabilityEmulationConfiguration estatEmulation = configuration.getConfigs().stream()
                    .filter(registry -> "ESTAT".equals(registry.getName()))
                    .flatMap(registry -> registry.getVersions().values().stream())
                    .map(VersionSpecificRegistryConfiguration::getAvailabilityEndpointConfig)
                    .map(AvailabilityEndpointConfiguration::getEmulation)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ESTAT must configure availability emulation"));
            assertEquals(AvailabilityEmulationType.DATA_QUERY, estatEmulation.getType());
            assertEquals(SdmxFormat.CSV_DATA_1_0_0, estatEmulation.getProbeFormat());
        }
    }

    @Test
    void rejectsEmulationWhileAvailabilityIsEnabled() {
        // Both on would leave it unclear which answers a request, and the proxy would silently
        // pick the endpoint.
        RegistryConfiguration registry = estatWithEmulation(estatEmulation());
        availabilityOf(registry).setAvailabilityEnabled(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(configWith(registry)));
        assertTrue(e.getMessage().contains("availabilityEnabled is true"), e.getMessage());
    }

    @Test
    void rejectsEmulationCombinedWithLimitEmulation() {
        // Limit emulation probes availability to shrink a query cheaply. With availability itself
        // emulated by a data query, every probe becomes a data request -- see designs 014 and 040.
        RegistryConfiguration registry = estatWithEmulation(estatEmulation());
        versionOf(registry).getDataEndpointConfig().setSupportsLimit(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(configWith(registry)));
        assertTrue(e.getMessage().contains("supportsLimit"), e.getMessage());
    }

    @Test
    void rejectsConstraintSourceMissingFromSupportedStructures() {
        // Otherwise the internal constraint query is rejected at translation time and the client
        // sees an opaque 500 instead of a configuration error.
        RegistryConfiguration registry = estatWithEmulation(estatEmulation());
        versionOf(registry).getStructureEndpointConfig().setSupportedStructures(Set.of("dataflow", "datastructure"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(configWith(registry)));
        assertTrue(e.getMessage().contains("contentconstraint"), e.getMessage());
    }

    @Test
    void rejectsProbeFormatTheDataEndpointDoesNotServe() {
        AvailabilityEmulationConfiguration emulation = estatEmulation();
        emulation.setProbeFormat(SdmxFormat.JSON_DATA_2_0_0);
        RegistryConfiguration registry = estatWithEmulation(emulation);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(configWith(registry)));
        assertTrue(e.getMessage().contains("probeFormat"), e.getMessage());
    }

    @Test
    void rejectsProbeDetailOnSdmx30() {
        // `detail` exists only in SDMX-REST 1.5.0; SDMX-REST 2.x replaced it with attributes and
        // measures, which the probe sends instead.
        AvailabilityEmulationConfiguration emulation = estatEmulation();
        emulation.setConstraintStructureType("dataconstraint");
        RegistryConfiguration registry = estatWithEmulation(emulation);
        VersionSpecificRegistryConfiguration version = versionOf(registry);
        version.setSdmxVersion(SdmxVersion.SDMX_3_0);
        version.getStructureEndpointConfig().setSupportedStructures(Set.of("dataflow", "dataconstraint"));
        registry.setVersions(Map.of(SdmxVersion.SDMX_3_0, version));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(configWith(registry)));
        assertTrue(e.getMessage().contains("probeDetail"), e.getMessage());
    }

    @Test
    void acceptsTheEstatEmulationConfiguration() {
        assertDoesNotThrow(() -> validator.validate(configWith(estatWithEmulation(estatEmulation()))));
    }

    @Test
    void ignoresEmulationBlockWithTypeNone() {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        RegistryConfiguration registry = estatWithEmulation(emulation);
        // Everything the other cases reject is present, but type NONE means no emulation runs.
        versionOf(registry).getDataEndpointConfig().setSupportsLimit(false);
        versionOf(registry).getStructureEndpointConfig().setSupportedStructures(Set.of("dataflow"));

        assertDoesNotThrow(() -> validator.validate(configWith(registry)));
    }

    private AvailabilityEmulationConfiguration estatEmulation() {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        emulation.setType(AvailabilityEmulationType.DATA_QUERY);
        emulation.setProbeFormat(SdmxFormat.CSV_DATA_1_0_0);
        return emulation;
    }

    private RegistryConfiguration estatWithEmulation(AvailabilityEmulationConfiguration emulation) {
        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setSupportedStructures(Set.of("dataflow", "datastructure", "codelist", "contentconstraint"));

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setSupportedFormats(List.of(SdmxFormat.XML_GENERIC_DATA_2_1, SdmxFormat.CSV_DATA_1_0_0));
        dataConfig.setDefaultFormat(SdmxFormat.XML_GENERIC_DATA_2_1);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(false);
        availabilityConfig.setEmulation(emulation);

        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_2_1);
        version.setStructureEndpointConfig(structureConfig);
        version.setDataEndpointConfig(dataConfig);
        version.setAvailabilityEndpointConfig(availabilityConfig);

        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("ESTAT");
        registry.setVersions(Map.of(SdmxVersion.SDMX_2_1, version));
        return registry;
    }

    private VersionSpecificRegistryConfiguration versionOf(RegistryConfiguration registry) {
        return registry.getVersions().values().iterator().next();
    }

    private AvailabilityEndpointConfiguration availabilityOf(RegistryConfiguration registry) {
        return versionOf(registry).getAvailabilityEndpointConfig();
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
