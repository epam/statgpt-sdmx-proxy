package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when registry configuration is null or invalid.
 */
public class IllegalRegistryConfigurationException extends RuntimeException {
    public IllegalRegistryConfigurationException(String message) {
        super(message);
    }

    public IllegalRegistryConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
