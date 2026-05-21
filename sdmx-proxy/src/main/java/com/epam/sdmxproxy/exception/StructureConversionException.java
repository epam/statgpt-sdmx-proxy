package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when the structure conversion pipeline fails (parse, re-serialize, or
 * upstream read of structural metadata). Maps to HTTP 500.
 */
public class StructureConversionException extends ServerErrorException {

    public StructureConversionException(String message) {
        super(message);
    }

    public StructureConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
