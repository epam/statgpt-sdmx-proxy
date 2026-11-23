package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.PreheatConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 044, step 2: a preheat-only configuration change must not invalidate the caches.
 */
class ProxyConfigurationComparatorTest {

    private static final Set<String> COPIED_FIELDS = Set.of("configs", "agencies", "structureFanOutEnabled");

    @Test
    void shouldIgnorePreheatChanges() {
        ProxyConfiguration left = configuration("OECD");
        ProxyConfiguration right = configuration("OECD");
        right.setPreheat(List.of(group()));

        assertThat(ProxyConfigurationComparator.sameForCaching(left, right)).isTrue();
    }

    @Test
    void shouldDetectRegistryChanges() {
        assertThat(ProxyConfigurationComparator.sameForCaching(configuration("OECD"), configuration("BIS"))).isFalse();
    }

    @Test
    void shouldDetectFanOutToggle() {
        ProxyConfiguration right = configuration("OECD");
        right.setStructureFanOutEnabled(true);

        assertThat(ProxyConfigurationComparator.sameForCaching(configuration("OECD"), right)).isFalse();
    }

    @Test
    void shouldTreatNullAsDifferentFromConfiguration() {
        assertThat(ProxyConfigurationComparator.sameForCaching(null, configuration("OECD"))).isFalse();
        assertThat(ProxyConfigurationComparator.sameForCaching(null, null)).isTrue();
    }

    /**
     * Fails when a field is added to {@link ProxyConfiguration} without deciding whether a change
     * to it must invalidate the caches. Add it to the comparator's copy and to {@link #COPIED_FIELDS}.
     */
    @Test
    void shouldCoverEveryFieldExceptPreheat() {
        Set<String> declared = Arrays.stream(ProxyConfiguration.class.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers())).map(Field::getName).filter(name -> !"preheat".equals(name)).collect(Collectors.toSet());

        assertThat(declared).isEqualTo(COPIED_FIELDS);
    }

    private static ProxyConfiguration configuration(String registryName) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(registryName);
        ProxyConfiguration configuration = new ProxyConfiguration();
        configuration.setConfigs(List.of(registry));
        return configuration;
    }

    private static PreheatConfiguration group() {
        PreheatConfiguration group = new PreheatConfiguration();
        group.setMediaType("application/vnd.sdmx.structure+xml;version=2.1");
        group.setDataflows(List.of("OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)"));
        return group;
    }
}
