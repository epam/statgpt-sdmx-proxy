package com.epam.sdmxproxy.exception;

/**
 * Thrown when an upstream registry response exceeds the proxy's configured
 * in-memory processing limit. Maps to HTTP 500 via the ServerErrorException family.
 */
public class ResponseTooLargeException extends ServerErrorException {

    public ResponseTooLargeException(String message) {
        super(message);
    }

    public ResponseTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return "The upstream response was too large for the proxy to process. Narrow your query (e.g. add filters or a smaller time range) and try again.";
    }
}
