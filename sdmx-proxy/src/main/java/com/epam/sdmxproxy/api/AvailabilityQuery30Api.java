package com.epam.sdmxproxy.api;

import com.epam.sdmxproxy.common.data.AvailabilityQueryRequestDto;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;

@Tag(name = "SDMX Availability", description = "SDMX availability queries for data components")
@RequestMapping(value = {WebMvcSettings.API_PREFIX + "/sdmx/3.0/availability"})
public interface AvailabilityQuery30Api {

    @Operation(
            summary = "Query SDMX data availability",
            description = """
                    Retrieves availability information for SDMX data components from configured registries.
                    
                    Supports content negotiation via Accept header:
                    - application/vnd.sdmx.structure+json;version=2.0.0
                    - */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)
                    
                    **Path Variables**:
                    - context: Query context (datastructure, dataflow, or provisionagreement). Currently only 'dataflow' is supported.
                    - agencyID: Maintenance agency ID (e.g., "BIS", "IMF")
                    - resourceID: Resource identifier (e.g., dataflow ID)
                    - version: Version identifier (e.g., "1.0", "latest")
                    - key: Data key filter (SDMX key pattern)
                    - componentId: Component identifier to check availability for
                    
                    **Query Parameters**:
                    - updatedAfter: Filter availability information updated after this timestamp (ISO 8601 format)
                    - mode: Availability mode (default: exact)
                    - references: Reference inclusion mode (default: none)
                    - reportingYearStartDay: Start day for reporting year calculations
                    - Additional filter parameters can be passed as query parameters
                    
                    **Response**: SDMX availability data in JSON format.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Availability data retrieved successfully",
                    content = @Content(
                            mediaType = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - invalid parameters, unsupported context, or filter validation error"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Service unavailable - registry is down or circuit breaker is open"
            )
    })
    @GetMapping(
            value = "/{context:datastructure|dataflow|provisionagreement}/{agencyID}/{resourceID}/{version}/{key}/{componentId}",
            produces = {
                    SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                    SdmxMediaType.ANY
            }
    )
    ResponseEntity<StreamingResponseBody> availabilityQuery(
            @Parameter(
                    description = "Query context. Currently only 'dataflow' is supported.",
                    required = true,
                    example = "dataflow"
            )
            @PathVariable("context") String context,
            @Parameter(
                    description = "Maintenance agency ID",
                    required = true,
                    example = "BIS"
            )
            @PathVariable(value = "agencyID") String agencyID,
            @Parameter(
                    description = "Resource identifier (e.g., dataflow ID)",
                    required = true,
                    example = "WS_CBS_PUB"
            )
            @PathVariable(value = "resourceID") String resourceID,
            @Parameter(
                    description = "Version identifier",
                    required = true,
                    example = "1.0"
            )
            @PathVariable(value = "version") String version,
            @Parameter(
                    description = "Data key filter (SDMX key pattern)",
                    required = true,
                    example = "+"
            )
            @PathVariable(value = "key") String key,
            @Parameter(
                    description = "Component identifier to check availability for",
                    required = true,
                    example = "OBS_VALUE"
            )
            @PathVariable(value = "componentId") String componentId,
            @Parameter(
                    description = "Additional filter parameters as key-value pairs",
                    hidden = true
            )
            @RequestParam MultiValueMap<String, String> c,
            @Parameter(
                    description = "Filter availability information updated after this timestamp (ISO 8601 format)",
                    example = "2024-01-01T00:00:00Z"
            )
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @Parameter(
                    description = "Availability mode. Default: exact",
                    example = "exact"
            )
            @RequestParam(value = "mode", required = false, defaultValue = "exact") String mode,
            @Parameter(
                    description = "Reference inclusion mode. Default: none",
                    example = "none"
            )
            @RequestParam(value = "references", required = false, defaultValue = "none") String references,
            @Parameter(
                    description = "Start day for reporting year calculations",
                    example = "01-01"
            )
            @RequestParam(value = "reportingYearStartDay", required = false) String reportingYearStartDay,
            @Parameter(
                    description = "Accept header for content negotiation. Supported: application/vnd.sdmx.structure+json;version=2.0.0, */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)"
            )
            @RequestHeader(value = "Accept", required = false) @Nullable String accept,
            @Parameter(description = "URN of the source artefact that contained the cross-reference (for routing context)")
            @RequestHeader(value = "X-Source-Artefact-Urn", required = false) @Nullable String sourceArtefactUrn
    );

    @Operation(
            summary = "Query SDMX data availability (POST)",
            description = """
                    Retrieves availability information for SDMX data components from configured registries using POST method.
                    This endpoint provides a shorter URL by moving most parameters to the request body.
                    
                    Supports content negotiation via Accept header:
                    - application/vnd.sdmx.structure+json;version=2.0.0
                    - */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)
                    
                    **Path Variables**:
                    - context: Query context (datastructure, dataflow, or provisionagreement). Currently only 'dataflow' is supported.
                    - agencyID: Maintenance agency ID (e.g., "BIS", "IMF")
                    - resourceID: Resource identifier (e.g., dataflow ID)
                    - version: Version identifier (e.g., "1.0", "latest")
                    
                    **Request Body**:
                    - key: Data key filter (SDMX key pattern)
                    - componentId: Component identifier to check availability for
                    - updatedAfter: Filter availability information updated after this timestamp (ISO 8601 format)
                    - mode: Availability mode (default: exact)
                    - references: Reference inclusion mode (default: none)
                    - reportingYearStartDay: Start day for reporting year calculations
                    - c: List of filters (SDMX standard name). Each filter contains:
                      - componentCode: Component code to filter on
                      - value: Filter value
                      - operator: Filter operator (eq, ne, lt, le, gt, ge, co, nc, sw, ew). Default: eq
                    
                    **Response**: SDMX availability data in JSON format.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Availability data retrieved successfully",
                    content = @Content(
                            mediaType = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - invalid parameters, unsupported context, or filter validation error"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Service unavailable - registry is down or circuit breaker is open"
            )
    })
    @PostMapping(
            value = "/{context:datastructure|dataflow|provisionagreement}/{agencyID}/{resourceID}/{version}",
            consumes = "application/json",
            produces = {
                    SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                    SdmxMediaType.ANY
            }
    )
    ResponseEntity<StreamingResponseBody> availabilityQueryPost(
            @Parameter(
                    description = "Query context. Currently only 'dataflow' is supported.",
                    required = true,
                    example = "dataflow"
            )
            @PathVariable("context") String context,
            @Parameter(
                    description = "Maintenance agency ID",
                    required = true,
                    example = "BIS"
            )
            @PathVariable(value = "agencyID") String agencyID,
            @Parameter(
                    description = "Resource identifier (e.g., dataflow ID)",
                    required = true,
                    example = "WS_CBS_PUB"
            )
            @PathVariable(value = "resourceID") String resourceID,
            @Parameter(
                    description = "Version identifier",
                    required = true,
                    example = "1.0"
            )
            @PathVariable(value = "version") String version,
            @RequestBody(
                    description = "Availability query request with filters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = AvailabilityQueryRequestDto.class))
            )
            @org.springframework.web.bind.annotation.RequestBody AvailabilityQueryRequestDto request,
            @Parameter(
                    description = "Accept header for content negotiation. Supported: application/vnd.sdmx.structure+json;version=2.0.0, */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)"
            )
            @RequestHeader(value = "Accept", required = false) @Nullable String accept,
            @Parameter(description = "URN of the source artefact that contained the cross-reference (for routing context)")
            @RequestHeader(value = "X-Source-Artefact-Urn", required = false) @Nullable String sourceArtefactUrn
    );
}
