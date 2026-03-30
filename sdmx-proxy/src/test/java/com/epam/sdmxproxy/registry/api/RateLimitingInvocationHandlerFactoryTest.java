package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryRateLimitConfig;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.RateLimitExceededException;
import com.epam.sdmxproxy.registry.api.config.RateLimitProperties;
import com.epam.sdmxproxy.registry.api.config.ResilienceProperties;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for RateLimitingInvocationHandlerFactory.
 * Tests rate limiter creation and rate limiting behavior.
 */
class RateLimitingInvocationHandlerFactoryTest {

    private ResilienceProperties resilienceProperties;
    private RegistrySelectionResult selectedRegistry;

    @BeforeEach
    void setUp() {
        resilienceProperties = new ResilienceProperties();

        RateLimitProperties defaultRateLimit = new RateLimitProperties();
        defaultRateLimit.setLimitForPeriod(100);
        defaultRateLimit.setLimitRefreshPeriod(60000L);
        resilienceProperties.setDefaultRateLimit(defaultRateLimit);
        resilienceProperties.setDefaultRateLimitingEnabled(false);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TestRegistry");


        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl("http://test.com");
        structureConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        structureConfig.setDefaultFormat(ReturnFormat.XML_2_1);
        structureConfig.setBypassEnabled(true);
        structureConfig.setSupportedStructures(Set.of("datastructure", "dataflow"));
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

        registryConfig.setVersions(Map.of(SdmxVersion.SDMX_2_1, versionConfig));

        selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .build();
    }

    @Test
    @SneakyThrows
    void testCreate_WithRegistrySpecificConfig_WhenLimitExceeded() {
        // Given - Registry-specific config with limit of 5 requests
        // Note: Rate limiting must be explicitly enabled for the registry
        RegistryRateLimitConfig registryRateLimit = new RegistryRateLimitConfig();
        registryRateLimit.setEnabled(true); // Enable rate limiting for this registry
        registryRateLimit.setLimitForPeriod(5); // Registry-specific: 5 requests
        registryRateLimit.setLimitRefreshPeriod(1000L); // Registry-specific: 1 second
        selectedRegistry.getVersionConfiguration().getResilienceConfig().setRateLimit(registryRateLimit);

        // Default config has rate limiting turned off, so if registry config is used,
        // we should only be able to make 5 requests, not more than that

        RateLimitingInvocationHandlerFactory factory = new RateLimitingInvocationHandlerFactory(
                selectedRegistry, resilienceProperties);

        Target target = createMockTarget();
        Map<Method, MethodHandler> dispatch = createMockDispatch();

        // When
        InvocationHandler handler = factory.create(target, dispatch);
        Method testMethod = TestInterface.class.getMethod("testMethod");
        Object proxy = new Object();

        // Then - Verify registry-specific config is used by testing rate limiting behavior
        assertNotNull(handler);

        // Make exactly 5 requests (registry-specific limit) - all should succeed
        for (int i = 0; i < 5; i++) {
            Object result = handler.invoke(proxy, testMethod, new Object[]{});
            assertNotNull(result);
        }

        // The 6th request should fail with RateLimitExceededException
        // This proves the registry-specific limit (5) is used, not the default (100)
        assertThrows(RateLimitExceededException.class, () -> {
            handler.invoke(proxy, testMethod, new Object[]{});
        });
    }

    @Test
    @SneakyThrows
    void testCreate_WithDefaultConfig_WhenLimitExceeded() {
        // Given - No registry-specific rate limit config - should use defaults
        // Default config has limitForPeriod: 100, but rate limiting is disabled by default
        // So we need to enable it at the application level to test default config usage
        resilienceProperties.setDefaultRateLimitingEnabled(true);

        RateLimitingInvocationHandlerFactory factory = new RateLimitingInvocationHandlerFactory(
                selectedRegistry, resilienceProperties);

        Target target = createMockTarget();
        Map<Method, MethodHandler> dispatch = createMockDispatch();

        // When
        InvocationHandler handler = factory.create(target, dispatch);
        Method testMethod = TestInterface.class.getMethod("testMethod");
        Object proxy = new Object();

        // Then - Verify default config is used by testing rate limiting behavior
        assertNotNull(handler);

        // Make 100 requests (default limit) - all should succeed
        for (int i = 0; i < 100; i++) {
            Object result = handler.invoke(proxy, testMethod, new Object[]{});
            assertNotNull(result);
        }

        // The 101st request should fail with RateLimitExceededException
        // This proves the default limit (100) is used, not the registry-specific limit
        assertThrows(RateLimitExceededException.class, () -> {
            handler.invoke(proxy, testMethod, new Object[]{});
        });
    }

    @Test
    @SneakyThrows
    void testRateLimit_AllowsRequestsWithinLimit() {
        // Given - Higher limit
        RegistryRateLimitConfig registryRateLimit = new RegistryRateLimitConfig();
        registryRateLimit.setLimitForPeriod(10);
        registryRateLimit.setLimitRefreshPeriod(1000L);
        selectedRegistry.getVersionConfiguration().getResilienceConfig().setRateLimit(registryRateLimit);

        RateLimitingInvocationHandlerFactory factory = new RateLimitingInvocationHandlerFactory(
                selectedRegistry, resilienceProperties);

        Target target = createMockTarget();
        Map<Method, MethodHandler> dispatch = createMockDispatch();
        InvocationHandler handler = factory.create(target, dispatch);

        Method testMethod = TestInterface.class.getMethod("testMethod");
        Object proxy = new Object(); // Mock proxy object

        // When - Make multiple calls within limit
        for (int i = 0; i < 10; i++) {
            Object result = handler.invoke(proxy, testMethod, new Object[]{});
            assertNotNull(result);
        }

        // Then - All calls should succeed
    }

    // ========== Helper Methods ==========

    private Target createMockTarget() {
        return new Target.HardCodedTarget<>(TestInterface.class, "http://test.com", "TestTarget");
    }

    private Map<Method, MethodHandler> createMockDispatch() {
        Map<Method, MethodHandler> dispatch = new HashMap<>();
        try {
            Method testMethod = TestInterface.class.getMethod("testMethod");
            MethodHandler handler = args -> "success";
            dispatch.put(testMethod, handler);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        return dispatch;
    }

    // Test interface for creating mock targets
    interface TestInterface {
        String testMethod();
    }
}
