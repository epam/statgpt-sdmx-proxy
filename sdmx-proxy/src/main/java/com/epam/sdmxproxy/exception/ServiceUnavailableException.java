package com.epam.sdmxproxy.exception;

/**
 * Family parent for transient infrastructure / upstream failures (HTTP 503).
 *
 * <p>By default, {@link #getClientMessage()} returns a generic phrase so that internal
 * detail is not echoed to the client. Subclasses MAY override {@link #getClientMessage()}
 * when the message is curated and safe.
 */
public abstract class ServiceUnavailableException extends BaseException {

    private static final String GENERIC_CLIENT_MESSAGE = "A dependent service is currently unavailable.";

    protected ServiceUnavailableException(String message) {
        super(message);
    }

    protected ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return GENERIC_CLIENT_MESSAGE;
    }
}
