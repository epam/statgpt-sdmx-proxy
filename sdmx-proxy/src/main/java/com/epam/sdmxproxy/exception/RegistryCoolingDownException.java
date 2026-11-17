package com.epam.sdmxproxy.exception;

/**
 * Thrown while a structure key is in its rate-limit cooldown: the registry answered HTTP 429
 * after the full retry budget, and calling it again immediately would only spend more quota.
 * See design 042.
 */
public class RegistryCoolingDownException extends TooManyRequestsException {

    public RegistryCoolingDownException(String message) {
        super(message);
    }

    public RegistryCoolingDownException(String message, Throwable cause) {
        super(message, cause);
    }
}
