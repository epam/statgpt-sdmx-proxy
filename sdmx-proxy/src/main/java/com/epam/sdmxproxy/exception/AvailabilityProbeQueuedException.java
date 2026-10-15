package com.epam.sdmxproxy.exception;

/**
 * Thrown when an emulation data probe was answered with a queued-extraction envelope rather than
 * data, and the retry budget for that request was exhausted. Maps to HTTP 503, since the upstream
 * job may well have finished by the time the client retries.
 * <p>
 * Eurostat signals a queued extraction as HTTP 200 carrying a SOAP {@code <queued>} body with the
 * {@code Content-Type} of the requested format, so only the body distinguishes it from a real
 * answer. Detecting it matters: fed to a harvester it would yield an empty coverage map, which
 * the consumer reads as "nothing is available". See design 040.
 */
public class AvailabilityProbeQueuedException extends ServiceUnavailableException {

    public AvailabilityProbeQueuedException(String message) {
        super(message);
    }

    public AvailabilityProbeQueuedException(String message, Throwable cause) {
        super(message, cause);
    }
}
