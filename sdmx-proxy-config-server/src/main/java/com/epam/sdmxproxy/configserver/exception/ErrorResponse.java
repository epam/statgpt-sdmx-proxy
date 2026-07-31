package com.epam.sdmxproxy.configserver.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Error response model of the config server.
 *
 * <p>Mirrors the shape {@code ApiKeyAuthFilter} writes for 401 responses, so every
 * error this service returns carries the same two fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Error response containing the reason a request was rejected")
public class ErrorResponse {

    @Schema(description = "Human-readable error message", example = "Agency 'IMF' references unknown registry 'UNKNOWN'. Available registries: [BIS]")
    private String message;

    @Schema(description = "HTTP status code", example = "422")
    private Integer status;
}
