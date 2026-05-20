package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when an availability probe (used by limit-emulation) fails to query the
 * upstream registry or its response cannot be parsed. Maps to HTTP 503.
 */
public class AvailabilityProbeException extends ServiceUnavailableException {

    public AvailabilityProbeException(String message) {
        super(message);
    }

    public AvailabilityProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
