package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when a registry whose structures the proxy emulates is asked for every artefact
 * of a type it can serve only one at a time, for example all Statistics Canada codelists -- see
 * design 043.
 */
public class StructureListNotSupportedException extends NotImplementedException {

    public StructureListNotSupportedException(String message) {
        super(message);
    }

    public StructureListNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
