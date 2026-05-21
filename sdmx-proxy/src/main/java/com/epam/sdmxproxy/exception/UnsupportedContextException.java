package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when an unsupported context is provided in the request.
 */
public class UnsupportedContextException extends BadRequestException {

    public UnsupportedContextException(String message) {
        super(message);
    }

    public UnsupportedContextException(String message, Throwable cause) {
        super(message, cause);
    }
}
