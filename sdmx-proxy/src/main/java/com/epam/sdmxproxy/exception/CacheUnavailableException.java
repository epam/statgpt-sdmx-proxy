package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when the cache backend (Redis) is unreachable or its operations fail.
 * Maps to HTTP 503.
 */
public class CacheUnavailableException extends ServiceUnavailableException {

    public CacheUnavailableException(String message) {
        super(message);
    }

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
