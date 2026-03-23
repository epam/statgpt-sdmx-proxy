package com.epam.sdmxproxy.web.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Error response model that includes trace information for distributed tracing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Error response containing error details and trace information")
public class ErrorResponse {
    @Schema(description = "Human-readable error message", example = "Filter validation failed: The registry does not support these filters.")
    private String message;

    @Schema(description = "HTTP status code", example = "400")
    private Integer status;

    @Schema(description = "W3C Trace Context traceparent header value for distributed tracing", example = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
    private String traceparent;
}
