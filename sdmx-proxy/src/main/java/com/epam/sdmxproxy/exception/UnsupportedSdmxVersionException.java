package com.epam.sdmxproxy.exception;

public class UnsupportedSdmxVersionException extends BadRequestException {

    public UnsupportedSdmxVersionException(String message) {
        super(message);
    }

    public UnsupportedSdmxVersionException(String message, Throwable cause) {
        super(message, cause);
    }
}
