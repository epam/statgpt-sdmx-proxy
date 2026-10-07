package com.epam.sdmxproxy.exception;

/**
 * Thrown when availability emulation cannot produce a faithful answer -- a probe response the
 * harvester cannot read, a DSD dimension the response carries no column for, or a constraint that
 * omits a coded dimension. Maps to HTTP 500.
 * <p>
 * The emulator never degrades to an empty or partial cube instead of throwing. An empty
 * constraint raises in the consumer at dataset load anyway, and at query time it reads as
 * "nothing is available", which silently drops the dataset from the candidate set. See
 * design 040.
 */
public class AvailabilityEmulationException extends ServerErrorException {

    public AvailabilityEmulationException(String message) {
        super(message);
    }

    public AvailabilityEmulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
