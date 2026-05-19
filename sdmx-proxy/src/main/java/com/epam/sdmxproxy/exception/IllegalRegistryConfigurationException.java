package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when registry configuration is null or invalid.
 *
 * <p>This is an operator-side condition (the deployed configuration is wrong); the client
 * has no lever to fix it. Maps to HTTP 500 via the {@link ServerErrorException}
 * family.
 *
 * <p>The detail message is operator-friendly (registry name, missing field) and contains
 * no client-sensitive data, so {@link #getClientMessage()} echoes it instead of the
 * family-default generic message.
 */
public class IllegalRegistryConfigurationException extends ServerErrorException {

    public IllegalRegistryConfigurationException(String message) {
        super(message);
    }

    public IllegalRegistryConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getClientMessage() {
        return getMessage();
    }
}
