package com.epam.sdmxproxy.exception;

public class UnsupportedMediaTypeParameterException extends BadRequestException {

    public UnsupportedMediaTypeParameterException(String message) {
        super(message);
    }

    public UnsupportedMediaTypeParameterException(String message, Throwable cause) {
        super(message, cause);
    }
}
