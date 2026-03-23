package com.epam.sdmxproxy.web.config;

import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI configuration for SDMX Proxy API documentation.
 * Configures API metadata, server information, and custom tags.
 */
@Configuration
public class OpenApiConfig {

    private final WebMvcSettings webMvcSettings;

    public OpenApiConfig(WebMvcSettings webMvcSettings) {
        this.webMvcSettings = webMvcSettings;
    }

    @Bean
    public OpenAPI sdmxProxyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SDMX Proxy API")
                        .description("""
                                REST API for SDMX (Statistical Data and Metadata eXchange) data and structure queries.
                                
                                This proxy provides a unified interface to query multiple SDMX registries, supporting:
                                - SDMX 3.0 structure queries (datastructure, dataflow, codelist, etc.)
                                - SDMX 3.0 data queries with filtering and pagination
                                - SDMX 3.0 availability queries
                                - Fan-out support for querying multiple registries simultaneously
                                - Multiple response formats (SDMX JSON 1.0, 2.0, SDMX XML 2.1)
                                
                                All SDMX endpoints support content negotiation via the Accept header.
                                """)
                        .version("0.0.3-SNAPSHOT")
                        .contact(new Contact()
                                .name("EPAM Systems, Inc.")
                                .email("support@epam.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://www.epam.com")))
                .servers(List.of(
                        new Server()
                                .url("/")
                                .description("Default server (relative paths)")
                ))
                .tags(List.of(
                        new Tag()
                                .name("Health")
                                .description("Health check endpoints"),
                        new Tag()
                                .name("Configuration")
                                .description("Configuration management endpoints"),
                        new Tag()
                                .name("SDMX Structure")
                                .description("SDMX structure queries (datastructure, dataflow, codelist, etc.)"),
                        new Tag()
                                .name("SDMX Data")
                                .description("SDMX data queries with filtering and pagination"),
                        new Tag()
                                .name("SDMX Availability")
                                .description("SDMX availability queries for data components")
                ));
    }
}
