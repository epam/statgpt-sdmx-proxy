package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when rate limit is exceeded for a registry.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }

    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
