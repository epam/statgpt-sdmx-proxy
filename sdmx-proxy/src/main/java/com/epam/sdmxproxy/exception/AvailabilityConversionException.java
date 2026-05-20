package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when the availability conversion pipeline fails. Maps to HTTP 500.
 */
public class AvailabilityConversionException extends ServerErrorException {

    public AvailabilityConversionException(String message) {
        super(message);
    }

    public AvailabilityConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
