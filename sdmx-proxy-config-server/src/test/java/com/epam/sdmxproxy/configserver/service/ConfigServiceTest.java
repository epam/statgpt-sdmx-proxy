package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.epam.sdmxproxy.configserver.extractor.ConfigExtractor;
import com.epam.sdmxproxy.configserver.writer.ConfigWriter;
import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private ConfigExtractor configExtractor;

    @Mock
    private ConfigWriter configWriter;

    private final ConfigValidator configValidator = new ConfigValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initLoadsConfigFromExtractorOnSuccess() {
        ProxyConfiguration config = validConfig();
        when(configExtractor.getConfiguration()).thenReturn(config);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertTrue(service.isStorageAvailable());
        assertEquals(config, service.getConfiguration());
        verify(configWriter, never()).writeConfig(any());
    }

    @Test
    void initSeedsDialStorageWhenConfigIsNull() {
        when(configExtractor.getConfiguration()).thenReturn(null);
        when(configExtractor.sourceType()).thenReturn(ConfigSourceType.DIAL_STORAGE);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertTrue(service.isStorageAvailable());
        assertNotNull(service.getConfiguration());
        verify(configWriter).writeConfig(any(ProxyConfiguration.class));
    }

    @Test
    void initSeedsDialStorageWhenConfigIsEmpty() {
        ProxyConfiguration emptyConfig = new ProxyConfiguration();
        emptyConfig.setConfigs(List.of());
        emptyConfig.setAgencies(List.of());
        when(configExtractor.getConfiguration()).thenReturn(emptyConfig);
        when(configExtractor.sourceType()).thenReturn(ConfigSourceType.DIAL_STORAGE);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertTrue(service.isStorageAvailable());
        assertNotNull(service.getConfiguration());
        verify(configWriter).writeConfig(any(ProxyConfiguration.class));
    }

    @Test
    void initDoesNotSeedOnException() {
        when(configExtractor.getConfiguration()).thenThrow(new IllegalStateException("connection refused"));
        when(configExtractor.sourceType()).thenReturn(ConfigSourceType.DIAL_STORAGE);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertFalse(service.isStorageAvailable());
        assertNull(service.getConfiguration());
        verify(configWriter, never()).writeConfig(any());
    }

    @Test
    void initDoesNotSeedForFilesystemMode() {
        when(configExtractor.getConfiguration()).thenReturn(null);
        when(configExtractor.sourceType()).thenReturn(ConfigSourceType.FILESYSTEM);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertFalse(service.isStorageAvailable());
        assertNull(service.getConfiguration());
        verify(configWriter, never()).writeConfig(any());
    }

    @Test
    void initRemainsUnhealthyWhenWriterFails() {
        when(configExtractor.getConfiguration()).thenReturn(null);
        when(configExtractor.sourceType()).thenReturn(ConfigSourceType.DIAL_STORAGE);
        doThrow(new IllegalStateException("write failed")).when(configWriter).writeConfig(any());

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        assertFalse(service.isStorageAvailable());
        assertNull(service.getConfiguration());
    }

    @Test
    void updateConfigurationWritesAndSetsState() {
        ProxyConfiguration initial = validConfig();
        when(configExtractor.getConfiguration()).thenReturn(initial);

        ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
        service.init();

        ProxyConfiguration updated = validConfig();
        service.updateConfiguration(updated);

        assertEquals(updated, service.getConfiguration());
        assertTrue(service.isStorageAvailable());
        verify(configWriter).writeConfig(updated);
    }

    private ProxyConfiguration validConfig() {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(List.of(registry("BIS")));
        config.setAgencies(List.of(agency("BIS", "BIS")));
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
