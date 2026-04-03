package com.epam.sdmxproxy.exception;

public class UnsupportedSdmxVersionException extends IllegalArgumentException {
    public UnsupportedSdmxVersionException(String message) {
        super(message);
    }
}
