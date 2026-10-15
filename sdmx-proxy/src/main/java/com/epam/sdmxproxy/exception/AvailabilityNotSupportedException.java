package com.epam.sdmxproxy.exception;

/**
 * Thrown when a registry's availability endpoint is disabled
 * ({@code availabilityEndpointConfig.availabilityEnabled: false}) and no emulation is configured
 * for it. Maps to HTTP 501, which SDMX-REST defines as the status for an unimplemented method.
 * <p>
 * Deliberately not a passthrough of the registry's own status: Eurostat answers 405 on SDMX 2.1
 * and 404 on SDMX 3.0 for the same missing endpoint, and neither tells a client what actually
 * happened. See design 040.
 */
public class AvailabilityNotSupportedException extends NotImplementedException {

    public AvailabilityNotSupportedException(String message) {
        super(message);
    }

    public AvailabilityNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
