package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when a structure the proxy emulates does not exist, for example a Statistics
 * Canada codelist ID without a table number -- see design 043.
 */
public class StructureNotFoundException extends NotFoundException {

    public StructureNotFoundException(String message) {
        super(message);
    }

    public StructureNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
