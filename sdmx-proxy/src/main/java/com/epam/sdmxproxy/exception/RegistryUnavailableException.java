package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when a registry is unavailable due to circuit breaker being open.
 */
public class RegistryUnavailableException extends ServiceUnavailableException {

    public RegistryUnavailableException(String message) {
        super(message);
    }

    public RegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
