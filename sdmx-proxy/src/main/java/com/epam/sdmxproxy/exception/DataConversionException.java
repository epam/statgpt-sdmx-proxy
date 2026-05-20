package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when the data conversion pipeline fails. Maps to HTTP 500.
 */
public class DataConversionException extends ServerErrorException {

    public DataConversionException(String message) {
        super(message);
    }

    public DataConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
