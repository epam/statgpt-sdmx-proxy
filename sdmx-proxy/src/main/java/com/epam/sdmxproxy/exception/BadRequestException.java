package com.epam.sdmxproxy.exception;

/**
 * Family parent for failures caused by the client request (HTTP 400).
 * The client can fix this by changing the request.
 */
public abstract class BadRequestException extends BaseException {

    protected BadRequestException(String message) {
        super(message);
    }

    protected BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
