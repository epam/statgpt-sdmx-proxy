package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;

import java.util.Objects;

/**
 * Compares two proxy configurations for cache relevance. {@code preheat} only selects what to
 * warm and never makes a cached entry wrong, so it is ignored. See design 044.
 */
public final class ProxyConfigurationComparator {

    private ProxyConfigurationComparator() {
    }

    public static boolean sameForCaching(ProxyConfiguration left, ProxyConfiguration right) {
        return Objects.equals(withoutPreheat(left), withoutPreheat(right));
    }

    /**
     * Copies every field except {@code preheat}. {@code ProxyConfigurationComparatorTest} fails
     * when a field is added to {@link ProxyConfiguration} and not copied here.
     */
    static ProxyConfiguration withoutPreheat(ProxyConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        ProxyConfiguration copy = new ProxyConfiguration();
        copy.setConfigs(configuration.getConfigs());
        copy.setAgencies(configuration.getAgencies());
        copy.setStructureFanOutEnabled(configuration.isStructureFanOutEnabled());
        return copy;
    }
}
