package com.epam.sdmxproxy.exception;

/**
 * Exception thrown when the proxy is asked to convert between SDMX formats / versions that
 * it does not yet implement (e.g. JSON 2.1, XML 3.0). Maps to HTTP 501.
 */
public class UnsupportedConversionException extends NotImplementedException {

    public UnsupportedConversionException(String message) {
        super(message);
    }

    public UnsupportedConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
