package com.epam.sdmxproxy.exception;

/**
 * Thrown to a caller whose wait for a shared registry call exceeded the single-flight wait
 * timeout. The call itself keeps running and fills the cache -- see design 042.
 */
public class RegistryResponseTimeoutException extends ServiceUnavailableException {

    private static final String CLIENT_MESSAGE = "The registry has not answered yet. The request is still in progress; retry later.";

    public RegistryResponseTimeoutException(String message) {
        super(message);
    }

    public RegistryResponseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return CLIENT_MESSAGE;
    }
}
