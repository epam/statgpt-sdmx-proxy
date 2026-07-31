package com.epam.sdmxproxy.configserver.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for the config server.
 *
 * <p>{@code ConfigValidator} signals a rejected configuration with
 * {@link IllegalArgumentException}; that is a client-side problem and is answered with
 * 422 carrying the validation message, so callers can tell an invalid payload from a
 * server-side failure. Storage-write failures throw {@link IllegalStateException} and are
 * deliberately left to Spring's default 500 handling.
 */
@Slf4j
@RestControllerAdvice
public class ConfigServerExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(IllegalArgumentException ex) {
        log.warn("Configuration rejected: {}", ex.getMessage());
        ErrorResponse response = new ErrorResponse(ex.getMessage(), HttpStatus.UNPROCESSABLE_CONTENT.value());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON).body(response);
    }
}
