package com.epam.sdmxproxy.exception;

import java.util.List;

/**
 * Thrown when every registry leg of a structure fan-out request failed.
 * Maps to HTTP 503 via the {@link ServiceUnavailableException} family --
 * this is an upstream-infrastructure failure, not a client error.
 *
 * <p>The detail message lists the failed registry names; that is curated
 * and safe to echo, so {@link #getClientMessage()} returns it instead of
 * the family-default generic message.
 */
public class StructureFanOutException extends ServiceUnavailableException {

    private final List<String> failedRegistries;

    public StructureFanOutException(String message, List<String> failedRegistries) {
        super(message);
        this.failedRegistries = List.copyOf(failedRegistries);
    }

    public List<String> getFailedRegistries() {
        return failedRegistries;
    }

    @Override
    public String getClientMessage() {
        return getMessage();
    }
}
