package com.epam.sdmxproxy.exception;

/**
 * Family parent for "the proxy could implement this, but doesn't yet" (HTTP 501).
 * The thrower's message is echoed to the client unchanged.
 */
public abstract class NotImplementedException extends BaseException {

    protected NotImplementedException(String message) {
        super(message);
    }

    protected NotImplementedException(String message, Throwable cause) {
        super(message, cause);
    }
}
