package com.epam.sdmxproxy.registry.api.http;

/**
 * Seam over {@link Thread#sleep(long)} so retry backoff can be unit-tested without real waiting.
 */
public interface Sleeper {

    /**
     * Sleeps for the given duration.
     *
     * @return {@code true} when the full duration elapsed, {@code false} when the thread was
     * interrupted (in which case the interrupt flag is restored and the caller must stop retrying)
     */
    boolean sleep(long millis);
}
