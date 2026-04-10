package com.epam.sdmxproxy.configserver.api;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Config Server", description = "SDMX Proxy configuration management")
@RequestMapping("/statgpt/sdmx-proxy/api/v0/config")
public interface ConfigServerApi {

    @Operation(summary = "Get current proxy configuration")
    @ApiResponse(responseCode = "200", description = "Current configuration")
    @ApiResponse(responseCode = "404", description = "No configuration available (cold start)")
    @ApiResponse(responseCode = "503", description = "DIAL Storage unavailable")
    @GetMapping
    ResponseEntity<ProxyConfiguration> getConfig();

    @Operation(summary = "Update proxy configuration")
    @ApiResponse(responseCode = "200", description = "Configuration updated successfully")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @PostMapping
    ResponseEntity<ProxyConfiguration> updateConfig(@RequestBody ProxyConfiguration configuration);
}
