package com.epam.sdmxproxy.registry.api.http;

import org.springframework.stereotype.Component;

/**
 * Production {@link Sleeper}: sleeps on the calling thread and restores the interrupt flag.
 */
@Component
public class ThreadSleeper implements Sleeper {

    @Override
    public boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
