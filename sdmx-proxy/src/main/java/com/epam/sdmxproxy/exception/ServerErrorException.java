package com.epam.sdmxproxy.exception;

/**
 * Family parent for server-side bugs and invariant violations (HTTP 500).
 *
 * <p>By default, {@link #getClientMessage()} returns a generic phrase so that internal
 * detail is not echoed to the client. Subclasses MAY override {@link #getClientMessage()}
 * when the message is curated and safe (e.g. operator-visible misconfiguration).
 */
public abstract class ServerErrorException extends BaseException {

    private static final String GENERIC_CLIENT_MESSAGE = "An internal server error occurred.";

    protected ServerErrorException(String message) {
        super(message);
    }

    protected ServerErrorException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return GENERIC_CLIENT_MESSAGE;
    }
}
