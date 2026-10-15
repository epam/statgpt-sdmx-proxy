package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.services.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Design 041 / P8. The comparison is not optional: ConfigServerPoller re-applies configuration
 * every 30 seconds by default, so an unconditional flush would empty the cache twice a minute.
 */
class ProxyConfigurationProviderImplTest {

    private CacheService cacheService;
    private ProxyConfigurationProviderImpl sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cacheService = mock(CacheService.class);
        ObjectProvider<CacheService> cacheServiceProvider = mock(ObjectProvider.class);
        when(cacheServiceProvider.getObject()).thenReturn(cacheService);
        sut = new ProxyConfigurationProviderImpl(Set.of(), cacheServiceProvider);
    }

    @Test
    void shouldInvalidateCacheWhenConfigurationChanges() {
        sut.setRuntimeOverride(configuration("ESTAT"));
        verify(cacheService, times(1)).invalidateAll();

        sut.setRuntimeOverride(configuration("BIS"));
        verify(cacheService, times(2)).invalidateAll();
    }

    @Test
    void shouldNotInvalidateCacheWhenConfigurationIsUnchanged() {
        sut.setRuntimeOverride(configuration("ESTAT"));
        verify(cacheService, times(1)).invalidateAll();

        sut.setRuntimeOverride(configuration("ESTAT"));
        verify(cacheService, times(1)).invalidateAll();
    }

    @Test
    void shouldNotInvalidateCacheWhenOverrideStaysNull() {
        sut.setRuntimeOverride(null);
        verify(cacheService, never()).invalidateAll();
    }

    private ProxyConfiguration configuration(String registryName) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(registryName);
        ProxyConfiguration configuration = new ProxyConfiguration();
        configuration.setConfigs(List.of(registry));
        return configuration;
    }
}
