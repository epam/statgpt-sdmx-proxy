package com.epam.sdmxproxy.web.exception;

import com.epam.sdmxproxy.configuration.telemetry.TraceContextUtils;
import com.epam.sdmxproxy.exception.AgencyRoutingException;
import com.epam.sdmxproxy.exception.FilterValidationException;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.exception.RateLimitExceededException;
import com.epam.sdmxproxy.exception.RegistryUnavailableException;
import com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException;
import com.epam.sdmxproxy.exception.UnsupportedContextException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler for all controllers.
 * Handles exceptions and returns appropriate HTTP responses.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * Builds a JSON ResponseEntity carrying an ErrorResponse.
     * Content-Type is forced to application/json so that errors can still be rendered
     * when the client's Accept header only admits a non-JSON SDMX media type
     * (otherwise Spring's content negotiation throws HttpMediaTypeNotAcceptableException
     * while trying to write the ErrorResponse).
     */
    private ResponseEntity<ErrorResponse> buildErrorResponse(String message, HttpStatus status) {
        ErrorResponse response = new ErrorResponse();
        response.setMessage(message);
        response.setStatus(status.value());
        response.setTraceparent(TraceContextUtils.formatTraceParent());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /**
     * Handles unsupported agency wildcard/comma-separated requests.
     * Returns 501 Not Implemented.
     */
    @ApiResponse(responseCode = "501", description = "Not Implemented - Wildcard and comma-separated agency queries are not supported")
    @ExceptionHandler(UnsupportedAgencyWildcardException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedAgencyWildcardException(UnsupportedAgencyWildcardException ex) {
        log.warn("Unsupported agency wildcard: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_IMPLEMENTED);
    }

    /**
     * Handles agency routing exceptions (unsupported agency).
     * Returns 400 Bad Request with the error message.
     */
    @ApiResponse(responseCode = "400", description = "Bad Request - Agency routing failed")
    @ExceptionHandler(AgencyRoutingException.class)
    public ResponseEntity<ErrorResponse> handleAgencyRoutingException(AgencyRoutingException ex) {
        log.warn("Agency routing failed: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles filter validation exceptions.
     * Returns 400 Bad Request with the validation error message.
     */
    @ApiResponse(responseCode = "400", description = "Bad Request - Filter validation failed")
    @ExceptionHandler(FilterValidationException.class)
    public ResponseEntity<ErrorResponse> handleFilterValidationException(FilterValidationException ex) {
        log.warn("Filter validation failed: {}", ex.getMessage());
        String errorMessage = ex.getMessage() +
                " The registry does not support these filters. Please use another API endpoint or remove them.";
        return buildErrorResponse(errorMessage, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles feign exceptions, propagating upstream registry error messages to the client.
     */
    @ApiResponse(description = "Feign exception.")
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignException(FeignException ex) {
        log.warn("Feign exception. Status: {}. Message: {}", ex.status(), ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.status());
        String message = extractUpstreamErrorMessage(ex, status);
        return buildErrorResponse(message, status);
    }

    private String extractUpstreamErrorMessage(FeignException ex, HttpStatus status) {
        String body = ex.contentUTF8();
        if (body == null || body.isBlank()) {
            return status.getReasonPhrase();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode firstMessage = errors.get(0).path("message");
                if (!firstMessage.isMissingNode() && firstMessage.isTextual()) {
                    return firstMessage.asText();
                }
            }
            JsonNode message = root.path("message");
            if (!message.isMissingNode() && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Not valid JSON — fall through to raw body
        }
        return body;
    }

    /**
     * Handles unsupported context exceptions.
     * Returns 400 Bad Request with the error message.
     */
    @ApiResponse(responseCode = "400", description = "Bad Request - Unsupported context")
    @ExceptionHandler(UnsupportedContextException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedContextException(UnsupportedContextException ex) {
        log.warn("Unsupported context: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles illegal argument exceptions (e.g., unsupported context).
     * Returns 400 Bad Request with the error message.
     */
    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid argument")
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles illegal registry configuration exceptions (e.g., null registry configuration).
     * Returns 400 Bad Request with the error message.
     */
    @ApiResponse(responseCode = "400", description = "Bad Request - Illegal registry configuration")
    @ExceptionHandler(IllegalRegistryConfigurationException.class)
    public ResponseEntity<ErrorResponse> handleIllegalRegistryConfigurationException(IllegalRegistryConfigurationException ex) {
        log.warn("Illegal registry configuration: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles registry unavailable exceptions (circuit breaker open).
     * Returns 503 Service Unavailable.
     */
    @ApiResponse(responseCode = "503", description = "Service Unavailable - Registry is down or circuit breaker is open")
    @ExceptionHandler(RegistryUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleRegistryUnavailableException(RegistryUnavailableException ex) {
        log.warn("Registry unavailable: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handles rate limit exceeded exceptions.
     * Returns 429 Too Many Requests.
     */
    @ApiResponse(responseCode = "429", description = "Too Many Requests - Rate limit exceeded")
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceededException(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS);
    }

    @ApiResponse(responseCode = "404", description = "Not found - No resource found")
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleGenericException(NoResourceFoundException ex) {
        log.error("NoResourceFoundException", ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }


    @ApiResponse(responseCode = "400", description = "Bad request - http media not acceptable")
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleGenericException(HttpMediaTypeNotAcceptableException ex) {
        log.error("HttpMediaTypeNotAcceptableException ", ex);
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }


    /**
     * Handles all other exceptions.
     * Returns 500 Internal Server Error.
     */
    @ApiResponse(responseCode = "500", description = "Internal Server Error - An unexpected error occurred")
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return buildErrorResponse(
                "An unexpected error occurred. Please try again later.",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
