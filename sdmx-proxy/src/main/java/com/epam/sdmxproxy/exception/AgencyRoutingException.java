package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when an agency cannot be routed to any configured registry.
 */
public class AgencyRoutingException extends RuntimeException {

    public AgencyRoutingException(String message) {
        super(message);
    }
}
