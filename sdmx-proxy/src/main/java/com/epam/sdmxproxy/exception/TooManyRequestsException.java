package com.epam.sdmxproxy.exception;

/**
 * Family parent for rate-limit / quota failures (HTTP 429).
 */
public abstract class TooManyRequestsException extends BaseException {

    protected TooManyRequestsException(String message) {
        super(message);
    }

    protected TooManyRequestsException(String message, Throwable cause) {
        super(message, cause);
    }
}
