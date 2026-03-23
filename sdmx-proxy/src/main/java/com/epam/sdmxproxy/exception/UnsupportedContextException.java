package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when an unsupported context is provided in the request.
 */
public class UnsupportedContextException extends IllegalArgumentException {

    public UnsupportedContextException(String message) {
        super(message);
    }
}
