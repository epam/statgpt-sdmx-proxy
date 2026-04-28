package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryCircuitBreakerConfig;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistryRetryConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.registry.api.config.CircuitBreakerProperties;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import com.epam.sdmxproxy.registry.api.config.RetryProperties;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Resilience4jComponentFactory.
 * Tests circuit breaker and retry creation with various configuration scenarios.
 */
class Resilience4jComponentFactoryTest {

    private ResilienceProperties defaultConfig;
    private Resilience4jComponentFactory factory;

    @BeforeEach
    void setUp() {
        defaultConfig = new ResilienceProperties();

        CircuitBreakerProperties defaultCbConfig = new CircuitBreakerProperties();
        defaultCbConfig.setFailureRateThreshold(50.0f);
        defaultCbConfig.setMinimumNumberOfCalls(10);
        defaultCbConfig.setWaitDurationInOpenState(60000L);
        defaultCbConfig.setSlidingWindowSize(10);
        defaultConfig.setDefaultCircuitBreaker(defaultCbConfig);

        RetryProperties defaultRetryConfig = new RetryProperties();
        defaultRetryConfig.setMaxAttempts(5);
        defaultRetryConfig.setInitialIntervalMillis(500L);
        defaultRetryConfig.setMultiplier(2.0);
        defaultRetryConfig.setMaxIntervalMillis(2000L);
        defaultConfig.setDefaultRetry(defaultRetryConfig);

        factory = new Resilience4jComponentFactory(defaultConfig);
    }

    // ========== Circuit Breaker Tests ==========

    @Test
    void testGetOrCreateCircuitBreaker_WithRegistrySpecificConfig() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);
        RegistryCircuitBreakerConfig registryCbConfig = RegistryCircuitBreakerConfig.builder()
                .failureRateThreshold(40.0f)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(30000L)
                .slidingWindowSize(20)
                .build();
        selectedRegistry.getVersionConfiguration().getResilienceConfig().setCircuitBreaker(registryCbConfig);

        // When
        CircuitBreaker circuitBreaker = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");

        // Then
        assertNotNull(circuitBreaker);
        assertEquals("TestRegistry-SDMX_2_1-testoperation", circuitBreaker.getName());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        // Verify circuit breaker has the registry-specific config that was used for its creation
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig = circuitBreaker.getCircuitBreakerConfig();
        assertEquals(40.0f, cbConfig.getFailureRateThreshold(), 0.01f);
        assertEquals(5, cbConfig.getMinimumNumberOfCalls());
        assertEquals(20, cbConfig.getSlidingWindowSize());
    }

    @Test
    void testGetOrCreateCircuitBreaker_WithDefaultConfig() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);
        // No registry-specific circuit breaker config - should use defaults

        // When
        CircuitBreaker circuitBreaker = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");

        // Then
        assertNotNull(circuitBreaker);
        assertEquals("TestRegistry-SDMX_2_1-testoperation", circuitBreaker.getName());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        // Verify circuit breaker has the default config that was used for its creation
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig = circuitBreaker.getCircuitBreakerConfig();
        assertEquals(50.0f, cbConfig.getFailureRateThreshold(), 0.01f);
        assertEquals(10, cbConfig.getMinimumNumberOfCalls());
        assertEquals(10, cbConfig.getSlidingWindowSize());
    }

    @Test
    void testGetOrCreateCircuitBreaker_WithNullResilienceConfig() {
        // Given
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TestRegistry");


        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl("http://test.com");
        structureConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        structureConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        structureConfig.setBypassEnabled(true);
        versionConfig.setStructureEndpointConfig(structureConfig);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setUrl("http://test.com/data");
        dataConfig.setSupportedFormats(List.of(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
        dataConfig.setDefaultFormat(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1);
        dataConfig.setBypassEnabled(true);
        versionConfig.setDataEndpointConfig(dataConfig);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setUrl("http://test.com");
        availabilityConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        availabilityConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        availabilityConfig.setBypassEnabled(true);
        availabilityConfig.setAvailabilityEnabled(true);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);

        // resilienceConfig is null - should use defaults

        registryConfig.setVersions(java.util.Map.of(SdmxVersion.SDMX_2_1, versionConfig));

        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .build();

        // When
        CircuitBreaker circuitBreaker = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");

        // Then
        assertNotNull(circuitBreaker);
        assertEquals("TestRegistry-SDMX_2_1-testoperation", circuitBreaker.getName());

        // Verify circuit breaker has the default config that was used for its creation
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig cbConfig = circuitBreaker.getCircuitBreakerConfig();
        assertEquals(50.0f, cbConfig.getFailureRateThreshold(), 0.01f);
        assertEquals(10, cbConfig.getMinimumNumberOfCalls());
        assertEquals(10, cbConfig.getSlidingWindowSize());
    }

    @Test
    void testGetOrCreateCircuitBreaker_DifferentOperationNames() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);

        // When
        CircuitBreaker cb1 = factory.getOrCreateCircuitBreaker(selectedRegistry, "operation1");
        CircuitBreaker cb2 = factory.getOrCreateCircuitBreaker(selectedRegistry, "operation2");

        // Then
        assertNotNull(cb1);
        assertNotNull(cb2);
        assertNotEquals(cb1.getName(), cb2.getName());
        assertEquals("TestRegistry-SDMX_2_1-operation1", cb1.getName());
        assertEquals("TestRegistry-SDMX_2_1-operation2", cb2.getName());
    }

    @Test
    void testGetOrCreateCircuitBreaker_Caching() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);

        // When
        CircuitBreaker cb1 = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");
        CircuitBreaker cb2 = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");

        // Then
        // Note: Resilience4j creates new instances each time, so they won't be the same object
        // but they should have the same name
        assertNotNull(cb1);
        assertNotNull(cb2);
        assertEquals(cb1.getName(), cb2.getName());
        assertNotSame(cb1, cb2);
    }

    @Test
    void testGetOrCreateCircuitBreaker_RecordsOnlyUpstreamFailures() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);

        // When
        CircuitBreaker circuitBreaker = factory.getOrCreateCircuitBreaker(selectedRegistry, "testoperation");

        // Then
        Predicate<Throwable> recordPredicate = circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate();
        assertNotNull(recordPredicate);

        // Network/IO failures count as upstream-health failures
        assertTrue(recordPredicate.test(new IOException("connection reset")));

        // 5xx responses count
        assertTrue(recordPredicate.test(createFeignException(500)));
        assertTrue(recordPredicate.test(createFeignException(503)));

        // 4xx responses do NOT count -- they are valid registry answers about a client-input
        // domain (e.g. SDMX 404 "No results for query") and must not trip the breaker.
        assertFalse(recordPredicate.test(createFeignException(400)));
        assertFalse(recordPredicate.test(createFeignException(404)));

        // Unrelated exception types do not count
        assertFalse(recordPredicate.test(new IllegalArgumentException("not an upstream failure")));
    }

    // ========== Retry Tests ==========

    @Test
    void testGetOrCreateRetry_WithRegistrySpecificConfig() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);
        RegistryRetryConfig registryRetryConfig = RegistryRetryConfig.builder()
                .maxAttempts(3)
                .initialIntervalMillis(1000L)
                .multiplier(2.0)
                .maxIntervalMillis(4000L)
                .build();
        selectedRegistry.getVersionConfiguration().getResilienceConfig().setRetry(registryRetryConfig);

        // When
        Retry retry = factory.getOrCreateRetry(selectedRegistry);

        // Then
        assertNotNull(retry);
        assertEquals("TestRegistry-SDMX_2_1-retry", retry.getName());

        // Verify retry has the registry-specific config that was used for its creation
        io.github.resilience4j.retry.RetryConfig retryConfig = retry.getRetryConfig();
        assertEquals(3, retryConfig.getMaxAttempts());
        assertEquals(1000L, retryConfig.getIntervalFunction().apply(1));
        assertEquals(4000L, retryConfig.getIntervalFunction().apply(10));
    }

    @Test
    void testGetOrCreateRetry_WithDefaultConfig() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);
        // No registry-specific retry config - should use defaults

        // When
        Retry retry = factory.getOrCreateRetry(selectedRegistry);

        // Then
        assertNotNull(retry);
        assertEquals("TestRegistry-SDMX_2_1-retry", retry.getName());

        // Verify retry has the default config that was used for its creation
        io.github.resilience4j.retry.RetryConfig retryConfig = retry.getRetryConfig();
        assertEquals(5, retryConfig.getMaxAttempts());
        assertEquals(500L, retryConfig.getIntervalFunction().apply(1));
        assertEquals(2000L, retryConfig.getIntervalFunction().apply(10));
    }

    @Test
    void testGetOrCreateRetry_WithNullResilienceConfig() {
        // Given
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TestRegistry");


        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl("http://test.com");
        structureConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        structureConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        structureConfig.setBypassEnabled(true);
        versionConfig.setStructureEndpointConfig(structureConfig);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setUrl("http://test.com/data");
        dataConfig.setSupportedFormats(List.of(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
        dataConfig.setDefaultFormat(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1);
        dataConfig.setBypassEnabled(true);
        versionConfig.setDataEndpointConfig(dataConfig);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setUrl("http://test.com");
        availabilityConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        availabilityConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        availabilityConfig.setBypassEnabled(true);
        availabilityConfig.setAvailabilityEnabled(true);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);

        // resilienceConfig is null - should use defaults

        registryConfig.setVersions(java.util.Map.of(SdmxVersion.SDMX_2_1, versionConfig));

        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .build();

        // When
        Retry retry = factory.getOrCreateRetry(selectedRegistry);

        // Then
        assertNotNull(retry);
        assertEquals("TestRegistry-SDMX_2_1-retry", retry.getName());

        // Verify retry has the default config that was used for its creation
        io.github.resilience4j.retry.RetryConfig retryConfig = retry.getRetryConfig();
        assertEquals(5, retryConfig.getMaxAttempts());
        assertEquals(500L, retryConfig.getIntervalFunction().apply(1));
        assertEquals(2000L, retryConfig.getIntervalFunction().apply(10));
    }

    @Test
    void testGetOrCreateRetry_DifferentRegistries() {
        // Given
        RegistrySelectionResult selectedRegistry1 = createSelectedRegistry("Registry1", SdmxVersion.SDMX_2_1);
        RegistrySelectionResult selectedRegistry2 = createSelectedRegistry("Registry2", SdmxVersion.SDMX_2_1);

        // When
        Retry retry1 = factory.getOrCreateRetry(selectedRegistry1);
        Retry retry2 = factory.getOrCreateRetry(selectedRegistry2);

        // Then
        assertNotNull(retry1);
        assertNotNull(retry2);
        assertNotEquals(retry1.getName(), retry2.getName());
        assertEquals("Registry1-SDMX_2_1-retry", retry1.getName());
        assertEquals("Registry2-SDMX_2_1-retry", retry2.getName());
    }

    // ========== Null Parameter Handling Tests ==========

    @Test
    void testGetOrCreateCircuitBreaker_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            factory.getOrCreateCircuitBreaker(null, "testoperation");
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    @Test
    void testGetOrCreateCircuitBreaker_WithNullOperationName() {
        // Given
        RegistrySelectionResult selectedRegistry = createSelectedRegistry("TestRegistry", SdmxVersion.SDMX_2_1);

        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            factory.getOrCreateCircuitBreaker(selectedRegistry, null);
        });
        assertEquals("Operation name cannot be null", exception.getMessage());
    }

    @Test
    void testGetOrCreateRetry_WithNullRegistryConfig() {
        // When/Then
        IllegalRegistryConfigurationException exception = assertThrows(IllegalRegistryConfigurationException.class, () -> {
            factory.getOrCreateRetry(null);
        });
        assertEquals("Registry selection result cannot be null and must contain both registry and version configurations", exception.getMessage());
    }

    private static FeignException createFeignException(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "https://example.com/test", Collections.emptyMap(), null, new RequestTemplate());
        return FeignException.errorStatus("TestClient#method", Response.builder()
                .status(status)
                .reason("reason")
                .request(request)
                .headers(Collections.emptyMap())
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .build());
    }

    // ========== Helper Methods ==========

    private RegistrySelectionResult createSelectedRegistry(String name, SdmxVersion version) {
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName(name);


        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(version);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl("http://test.com");
        structureConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        structureConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        structureConfig.setBypassEnabled(true);
        structureConfig.setSupportedStructures(java.util.Set.of("datastructure", "dataflow"));
        versionConfig.setStructureEndpointConfig(structureConfig);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setUrl("http://test.com/data");
        dataConfig.setSupportedFormats(List.of(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
        dataConfig.setDefaultFormat(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1);
        dataConfig.setBypassEnabled(true);
        versionConfig.setDataEndpointConfig(dataConfig);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setUrl("http://test.com");
        availabilityConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        availabilityConfig.setDefaultFormat(ReturnFormat.XML_2_1);
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
