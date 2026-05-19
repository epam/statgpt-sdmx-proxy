package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when boot-time configuration cannot be loaded (config server
 * unreachable, classpath resource missing, file missing on disk). Maps to HTTP 503.
 *
 * <p>This exception typically fires during Spring startup before the
 * {@code GlobalExceptionHandler} is engaged, so the HTTP mapping is mostly cosmetic;
 * the class exists to keep the exception package consistent (no raw
 * {@code IllegalStateException} in our code).
 */
public class ConfigurationLoadException extends ServiceUnavailableException {

    public ConfigurationLoadException(String message) {
        super(message);
    }

    public ConfigurationLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
