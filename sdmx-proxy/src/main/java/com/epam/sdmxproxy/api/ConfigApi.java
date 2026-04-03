package com.epam.sdmxproxy.api;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Configuration", description = "Configuration management endpoints")
@RequestMapping(value = {WebMvcSettings.API_PREFIX + "/config"})
public interface ConfigApi {

    @Operation(
            summary = "Get configuration",
            description = "Retrieves the current proxy configuration including all registry settings. For debugging purposes."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Configuration retrieved successfully",
                    content = @Content(schema = @Schema(implementation = ProxyConfiguration.class))
            )
    })
    @GetMapping
    ResponseEntity<ProxyConfiguration> getConfig();

    @Operation(
            summary = "Update configuration (test only)",
            description = "Updates the proxy configuration. Only available when sdmxproxy.test.config-endpoint.enabled=true."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Configuration updated successfully"),
            @ApiResponse(responseCode = "404", description = "Endpoint not available")
    })
    @PostMapping(consumes = "application/json")
    ResponseEntity<?> updateConfig(
            @RequestBody(
                    description = "Proxy configuration with registry settings",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ProxyConfiguration.class))
            )
            @org.springframework.web.bind.annotation.RequestBody ProxyConfiguration proxyConfiguration
    );
}
