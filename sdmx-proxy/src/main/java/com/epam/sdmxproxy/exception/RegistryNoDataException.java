package com.epam.sdmxproxy.exception;

/**
 * Signals that the registry rejected a data query whose only fault is that it matches no
 * observations, so the proxy should answer with an empty result. Raised by
 * {@code AdapterRouterImpl} and consumed by the data and availability emulation paths; it never
 * reaches the client.
 */
public class RegistryNoDataException extends RuntimeException {

    public RegistryNoDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
