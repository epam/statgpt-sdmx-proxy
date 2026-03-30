package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when a wildcard (*) or comma-separated agency ID is used.
 * These are no longer supported; clients should use the /structure/agencyscheme endpoint instead.
 */
public class UnsupportedAgencyWildcardException extends RuntimeException {

    public UnsupportedAgencyWildcardException(String message) {
        super(message);
    }
}
