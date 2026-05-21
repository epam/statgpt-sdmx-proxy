package com.epam.sdmxproxy.exception;

/**
 * Bucket exception for {@code switch} defaults on exhaustive enums, invariant violations,
 * and other should-be-unreachable code paths. Maps to HTTP 500.
 *
 * <p>The detail message describes the invariant that was violated; the cause (when present)
 * carries the originating exception. Client receives a generic message.
 */
public class UnexpectedStateException extends ServerErrorException {

    public UnexpectedStateException(String message) {
        super(message);
    }

    public UnexpectedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
