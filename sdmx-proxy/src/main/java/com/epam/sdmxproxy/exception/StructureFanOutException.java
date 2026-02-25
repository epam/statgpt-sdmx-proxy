package com.epam.sdmxproxy.exception;

import java.util.List;

/**
 * Exception thrown when all registries fail during fan-out structure query execution.
 */
public class StructureFanOutException extends RuntimeException {
    private final List<String> failedRegistries;

    public StructureFanOutException(String message, List<String> failedRegistries) {
        super(message);
        this.failedRegistries = failedRegistries;
    }

    public StructureFanOutException(String message, List<String> failedRegistries, Throwable cause) {
        super(message, cause);
        this.failedRegistries = failedRegistries;
    }

    public List<String> getFailedRegistries() {
        return failedRegistries;
    }
}
