package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30StructureClient;
import com.epam.sdmxproxy.registry.api.config.InputStreamFeignDecoder;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.http.RateLimitRetryClientProvider;
import feign.Client;
import feign.codec.Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SdmxApiClientProviderImpl.
 * Tests client creation, caching, resilience integration, and configuration handling.
 */
class SdmxApiClientProviderImplTest {

    private Encoder encoder;
    private InputStreamFeignDecoder decoder;
    private Client baseOkHttpClient;
    private ResilienceProperties resilienceConfig;
    private Resilience4jComponentFactory resilience4JComponentFactory;
    private RateLimitRetryClientProvider rateLimitRetryClientProvider;
    private SdmxApiClientProviderImpl provider;

    @BeforeEach
    void setUp() {
        encoder = mock(Encoder.class);
        decoder = mock(InputStreamFeignDecoder.class);
        baseOkHttpClient = mock(Client.class);
        resilienceConfig = new ResilienceProperties();
        resilienceConfig.setDefaultConnectionTimeout(30000);
        resilienceConfig.setDefaultReadTimeout(30000);
        resilienceConfig.setDefaultRateLimitingEnabled(false);

        resilience4JComponentFactory = mock(Resilience4jComponentFactory.class);

        // Feign rejects a null client, so the wrapper must hand back the delegate by default.
        rateLimitRetryClientProvider = mock(RateLimitRetryClientProvider.class);
        when(rateLimitRetryClientProvider.wrap(any(), any())).thenReturn(baseOkHttpClient);

        provider = new SdmxApiClientProviderImpl(
                encoder,
                decoder,
                baseOkHttpClient,
                resilienceConfig,
                resilience4JComponentFactory,
                rateLimitRetryClientProvider
        );
    }

    // ========== Client Creation Tests ==========

    @Test
    void testGetData21Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21DataClient client = provider.getData21Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    @Test
    void testGetData30Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_3_0);

        // When
        Sdmx30DataClient client = provider.getData30Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    @Test
    void testGetStructure21Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21StructureClient client = provider.getStructure21Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    @Test
    void testGetStructure30Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_3_0);

        // When
        Sdmx30StructureClient client = provider.getStructure30Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    @Test
    void testGetAvailability21Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21AvailabilityClient client = provider.getAvailability21Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    @Test
    void testGetAvailability30Client_CreatesClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_3_0);

        // When
        Sdmx30AvailabilityClient client = provider.getAvailability30Client(selectedRegistry);

        // Then
        assertNotNull(client);
    }

    // ========== Caching Tests ==========

    @Test
    void testClientCaching_SameRegistryAndUrl_ReturnsSameInstance() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21DataClient client1 = provider.getData21Client(selectedRegistry);
        Sdmx21DataClient client2 = provider.getData21Client(selectedRegistry);

        // Then
        assertSame(client1, client2, "Should return the same cached client instance");
    }

    @Test
    void testClientCaching_DifferentUrls_CreatesDifferentInstances() {
        // Given
        RegistrySelectionResult selectedRegistry1 = createSelectedRegistry("TestRegistry", "http://test1.com", "http://test1.com/data", SdmxVersion.SDMX_2_1);
        RegistrySelectionResult selectedRegistry2 = createSelectedRegistry("TestRegistry", "http://test2.com", "http://test2.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21DataClient client1 = provider.getData21Client(selectedRegistry1);
        Sdmx21DataClient client2 = provider.getData21Client(selectedRegistry2);

        // Then
        assertNotSame(client1, client2, "Should create different clients for different URLs");
    }

    @Test
    void testClientCaching_DifferentClientTypes_CreatesDifferentInstances() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        Sdmx21DataClient dataClient = provider.getData21Client(selectedRegistry);
        Sdmx21StructureClient structureClient = provider.getStructure21Client(selectedRegistry);

        // Then
        assertNotSame(dataClient, structureClient, "Should create different clients for different types");
    }

    // ========== Resilience Integration Tests ==========

    @Test
    void testResilienceIntegration_CircuitBreakerCreated() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getData21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory, atLeastOnce())
                .getOrCreateCircuitBreaker(eq(selectedRegistry), anyString());
    }

    @Test
    void testResilienceIntegration_RetryCreated() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getData21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory, atLeastOnce())
                .getOrCreateRetry(eq(selectedRegistry));
    }

    @Test
    void testResilienceIntegration_DifferentOperationsGetDifferentCircuitBreakers() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getData21Client(selectedRegistry);
        provider.getStructure21Client(selectedRegistry);
        provider.getAvailability21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21dataclient"));
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21structureclient"));
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21availabilityclient"));
    }

    // ========== Null Parameter Handling Tests ==========

    @Test
    void testGetData21Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getData21Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetData30Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getData30Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetStructure21Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getStructure21Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetStructure30Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getStructure30Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetAvailability21Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getAvailability21Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetAvailability30Client_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            provider.getAvailability30Client(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    // ========== Operation Name Tests ==========

    @Test
    void testOperationName_DataClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getData21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21dataclient"));
    }

    @Test
    void testOperationName_StructureClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getStructure21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21structureclient"));
    }

    @Test
    void testOperationName_AvailabilityClient() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", "http://test.com", "http://test.com/data", SdmxVersion.SDMX_2_1);

        // When
        provider.getAvailability21Client(selectedRegistry);

        // Then
        verify(resilience4JComponentFactory)
                .getOrCreateCircuitBreaker(eq(selectedRegistry), eq("sdmx21availabilityclient"));
    }

    // ========== Helper Methods ==========

    private RegistrySelectionResult createSelectedRegistry(String name, String baseUrl, String dataUrl, SdmxVersion version) {
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName(name);


        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(version);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl(baseUrl);
        structureConfig.setSupportedFormats(java.util.List.of(SdmxFormat.XML_STRUCTURE_2_1));
        structureConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_2_1);
        structureConfig.setBypassEnabled(true);
        structureConfig.setSupportedStructures(java.util.Set.of("datastructure", "dataflow"));
        versionConfig.setStructureEndpointConfig(structureConfig);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setUrl(dataUrl);
        dataConfig.setSupportedFormats(java.util.List.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
        dataConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1);
        dataConfig.setBypassEnabled(true);
        versionConfig.setDataEndpointConfig(dataConfig);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setUrl(baseUrl);
        availabilityConfig.setSupportedFormats(java.util.List.of(SdmxFormat.XML_STRUCTURE_2_1));
        availabilityConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_2_1);
        availabilityConfig.setBypassEnabled(true);
        availabilityConfig.setAvailabilityEnabled(true);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);

        versionConfig.setResilienceConfig(new RegistryResilienceConfig());

        registryConfig.setVersions(java.util.Map.of(version, versionConfig));

        return RegistrySelectionResult.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .build();
    }
}
