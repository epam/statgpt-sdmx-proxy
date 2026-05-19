package com.epam.sdmxproxy.web.exception;

import com.epam.sdmxproxy.configuration.telemetry.TraceContextUtils;
import com.epam.sdmxproxy.exception.BadRequestException;
import com.epam.sdmxproxy.exception.FilterValidationException;
import com.epam.sdmxproxy.exception.NotImplementedException;
import com.epam.sdmxproxy.exception.ServerErrorException;
import com.epam.sdmxproxy.exception.ServiceUnavailableException;
import com.epam.sdmxproxy.exception.TooManyRequestsException;
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
 *
 * <p>Dispatches on the {@code BaseException} family hierarchy: one method per HTTP
 * status family. External exceptions (Feign, Spring) keep dedicated handlers.
 *
 * <p>Logging and message policy is enforced here, not at the throw site:
 * <ul>
 *   <li>4xx / 501 families log at {@code warn} without stack and echo the throw-site
 *       message verbatim.</li>
 *   <li>5xx families log at {@code error} with stack and use the family's generic
 *       client message (subclasses may override to expose operator-friendly detail).</li>
 *   <li>The catch-all {@code Exception} handler is reached only by unmapped exceptions
 *       and always logs at {@code error} with stack.</li>
 * </ul>
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
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @ApiResponse(responseCode = "400", description = "Bad Request - client input is invalid")
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        String message = ex.getClientMessage();
        if (ex instanceof FilterValidationException) {
            message = message + " The registry does not support these filters. Please use another API endpoint or remove them.";
        }
        return buildErrorResponse(message, HttpStatus.BAD_REQUEST);
    }

    @ApiResponse(responseCode = "501", description = "Not Implemented - the proxy does not implement this operation")
    @ExceptionHandler(NotImplementedException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(NotImplementedException ex) {
        log.warn("Not implemented: {}", ex.getMessage());
        return buildErrorResponse(ex.getClientMessage(), HttpStatus.NOT_IMPLEMENTED);
    }

    @ApiResponse(responseCode = "429", description = "Too Many Requests - rate limit exceeded")
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Too many requests: {}", ex.getMessage());
        return buildErrorResponse(ex.getClientMessage(), HttpStatus.TOO_MANY_REQUESTS);
    }

    @ApiResponse(responseCode = "500", description = "Internal Server Error - server-side bug or misconfiguration")
    @ExceptionHandler(ServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleServerError(ServerErrorException ex) {
        log.error("Server error: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getClientMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ApiResponse(responseCode = "503", description = "Service Unavailable - a dependent service failed")
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex.getClientMessage(), HttpStatus.SERVICE_UNAVAILABLE);
    }

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
            // Not valid JSON - fall through to raw body
        }
        return body;
    }

    @ApiResponse(responseCode = "404", description = "Not found - No resource found")
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("Resource not found: {}", ex.getResourcePath());
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ApiResponse(responseCode = "400", description = "Bad request - http media not acceptable")
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("Media type not acceptable: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Catch-all for anything not mapped above. Reaching this handler indicates either a raw
     * JDK runtime exception that slipped through migration, or an exception from a library
     * (sdmx-core, Jackson) that we have not wrapped. Logged at error with stack; client
     * receives a generic message.
     */
    @ApiResponse(responseCode = "500", description = "Internal Server Error - An unexpected error occurred")
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildErrorResponse("An unexpected error occurred. Please try again later.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
