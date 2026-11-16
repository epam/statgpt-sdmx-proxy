package com.epam.sdmxproxy.exception;

/**
 * Family parent for "the requested resource does not exist" (HTTP 404).
 * The thrower's message is echoed to the client unchanged.
 */
public abstract class NotFoundException extends BaseException {

    protected NotFoundException(String message) {
        super(message);
    }

    protected NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
