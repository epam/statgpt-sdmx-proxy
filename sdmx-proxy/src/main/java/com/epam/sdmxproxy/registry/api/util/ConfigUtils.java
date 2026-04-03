package com.epam.sdmxproxy.registry.api.util;

import lombok.NonNull;

import java.util.function.Supplier;

/**
 * Utility class for configuration value resolution with fallback to defaults.
 */
public class ConfigUtils {

    /**
     * Gets a value from registry config with fallback to default config.
     * Returns registry value if not null, otherwise returns default value.
     *
     * @param registryValue   the value from registry configuration (may be null)
     * @param defaultSupplier supplier for the default value
     * @param <T>             the type of the value
     * @return registry value if not null, otherwise default value
     */
    @NonNull
    public static <T> T getWithFallbackToDefault(T registryValue, Supplier<T> defaultSupplier) {
        return registryValue != null ? registryValue : defaultSupplier.get();
    }
}
