package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.PreheatConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.services.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

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
    private ApplicationEventPublisher eventPublisher;
    private ProxyConfigurationProviderImpl sut;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cacheService = mock(CacheService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectProvider<CacheService> cacheServiceProvider = mock(ObjectProvider.class);
        when(cacheServiceProvider.getObject()).thenReturn(cacheService);
        sut = new ProxyConfigurationProviderImpl(Set.of(), cacheServiceProvider, eventPublisher);
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

    @Test
    void shouldKeepCachesOnPreheatOnlyChange() {
        sut.setRuntimeOverride(configuration("OECD"));
        verify(cacheService, times(1)).invalidateAll();

        ProxyConfiguration withPreheat = configuration("OECD");
        PreheatConfiguration group = new PreheatConfiguration();
        group.setMediaType("application/vnd.sdmx.structure+xml;version=2.1");
        group.setDataflows(List.of("OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)"));
        withPreheat.setPreheat(List.of(group));
        sut.setRuntimeOverride(withPreheat);

        verify(cacheService, times(1)).invalidateAll();
        verify(eventPublisher).publishEvent(new ProxyConfigurationChangedEvent("runtime-override", false));
    }

    @Test
    void shouldPublishInvalidatingEventOnRegistryChange() {
        sut.setRuntimeOverride(configuration("ESTAT"));

        verify(eventPublisher).publishEvent(new ProxyConfigurationChangedEvent("runtime-override", true));
    }

    private ProxyConfiguration configuration(String registryName) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(registryName);
        ProxyConfiguration configuration = new ProxyConfiguration();
        configuration.setConfigs(List.of(registry));
        return configuration;
    }
}
