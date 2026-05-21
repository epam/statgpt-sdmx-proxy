package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when an agency cannot be routed to any configured registry.
 */
public class AgencyRoutingException extends BadRequestException {

    public AgencyRoutingException(String message) {
        super(message);
    }

    public AgencyRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
