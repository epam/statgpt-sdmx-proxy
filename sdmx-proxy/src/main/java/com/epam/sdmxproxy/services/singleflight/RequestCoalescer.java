package com.epam.sdmxproxy.services.singleflight;

import java.util.function.Supplier;

/**
 * Collapses concurrent calls that share a key into one execution of {@code loader}. Every caller
 * receives the same bytes or the same exception. Implementations decide where the in-flight
 * registry lives: in this process, or in a shared store for a multi-instance deployment.
 * See design 042.
 */
public interface RequestCoalescer {

    /**
     * Returns the loader's result for {@code key}, running {@code loader} only if no call for
     * {@code key} is already in flight.
     *
     * @throws com.epam.sdmxproxy.exception.RegistryResponseTimeoutException if the wait exceeds the
     *                                                                       configured timeout; the
     *                                                                       shared call is not cancelled
     */
    byte[] coalesce(String key, Supplier<byte[]> loader);
}
