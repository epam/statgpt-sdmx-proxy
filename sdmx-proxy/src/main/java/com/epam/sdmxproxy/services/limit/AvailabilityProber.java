package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;

import java.io.InputStream;

/**
 * Callback handed to {@link LimitEmulationService} by {@code AdapterRouter} so the shrink
 * loop can issue availability probes without depending on {@code GenericRegistryAdapter}
 * directly. In production the binding is {@code genericRegistryAdapter::getAvailability};
 * tests pass a lambda returning a pre-baked fixture stream.
 * <p>
 * Enforces the architectural rule that only {@code AdapterRouter} talks to
 * {@code GenericRegistryAdapter}: the emulation service never sees the adapter.
 */
@FunctionalInterface
public interface AvailabilityProber {

    InputStream probe(TranslatedAvailabilityQuery availabilityQuery);
}
