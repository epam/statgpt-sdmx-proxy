package com.epam.sdmxproxy.configserver.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Health", description = "Health check endpoints")
@RequestMapping(value = {"/health", "/"})
public interface HealthCheckApi {

    @Operation(
            summary = "Health check",
            description = "Returns the health status of the application. Returns 'OK' if the service is running."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Service is healthy",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    @GetMapping
    ResponseEntity<String> health();
}
