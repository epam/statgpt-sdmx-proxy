package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when filter validation fails.
 * Indicates that the provided filters are not compatible with the target registry.
 */
public class FilterValidationException extends BadRequestException {

    public FilterValidationException(String message) {
        super(message);
    }

    public FilterValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
