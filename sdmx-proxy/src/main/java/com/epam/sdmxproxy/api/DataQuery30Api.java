package com.epam.sdmxproxy.api;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;

@Tag(name = "SDMX Data", description = "SDMX data queries with filtering and pagination")
@RequestMapping(value = {WebMvcSettings.API_PREFIX + "/sdmx/3.0/data"})
public interface DataQuery30Api {

    @Operation(
            summary = "Query SDMX data",
            description = """
                    Retrieves SDMX data observations from configured registries based on the specified query parameters.
                    
                    Supports content negotiation via Accept header:
                    - application/json
                    - application/vnd.sdmx.data+json;version=1.0.0
                    - application/vnd.sdmx.data+json;version=2.0.0
                    - application/vnd.sdmx.data+xml;version=3.0.0
                    - application/vnd.sdmx.data+csv;version=2.0.0 (supports parameters: labels=[id|name|both], timeFormat=[original|normalized], keys=[none|obs|series|both])
                    - text/csv, application/csv (same CSV parameters supported)
                    - application/xml
                    - */* (defaults to application/vnd.sdmx.data+json;version=2.0.0)
                    
                    **Path Variables**:
                    - context: Query context (datastructure, dataflow, or provisionagreement). Currently only 'dataflow' is supported.
                    - agencyID: Maintenance agency ID (e.g., "BIS", "IMF")
                    - resourceID: Resource identifier (e.g., dataflow ID)
                    - version: Version identifier (e.g., "1.0", "latest")
                    - key: Data key filter (SDMX key pattern, use '+' for all, '.' for partial match)
                    
                    **Query Parameters**:
                    - updatedAfter: Filter observations updated after this timestamp (ISO 8601 format)
                    - firstNObservations: Return only the first N observations
                    - lastNObservations: Return only the last N observations
                    - dimensionAtObservation: Dimension to use for observation-level data (default: TIME_PERIOD)
                    - attributes: Attribute inclusion mode (default: dsd)
                    - measures: Measure inclusion mode (default: all)
                    - includeHistory: Include historical revisions (default: false)
                    - limit: Maximum number of observations to return
                    - asOf: Return data as it existed at this timestamp (ISO 8601 format)
                    - skipEmptySeries: Skip series with no observations (default: false)
                    - Additional filter parameters can be passed as query parameters matching dimension/attribute names
                    
                    **Response**: Streaming SDMX data response in the requested format.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Data retrieved successfully",
                    content = {
                            @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = SdmxMediaType.SDMX_JSON_1_0_0_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = SdmxMediaType.SDMX_JSON_2_0_0_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = SdmxMediaType.SDMX_XML_3_0_0_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = SdmxMediaType.SDMX_CSV_2_0_0_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            )
                    }
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
            value = "/{context:datastructure|dataflow|provisionagreement}/{agencyID}/{resourceID}/{version}/{key}",
            produces = {
                    MediaType.APPLICATION_JSON_VALUE,
                    SdmxMediaType.SDMX_JSON_1_0_0_VALUE,
                    SdmxMediaType.SDMX_JSON_2_0_0_VALUE,
                    SdmxMediaType.SDMX_XML_3_0_0_VALUE,
                    SdmxMediaType.SDMX_CSV_2_0_0_VALUE,
                    SdmxMediaType.APPLICATION_CSV_VALUE,
                    SdmxMediaType.TEXT_CSV_VALUE,
                    MediaType.APPLICATION_XML_VALUE,
                    MediaType.ALL_VALUE
            }
    )
    ResponseEntity<StreamingResponseBody> dataQuery(
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
                    description = "Data key filter (SDMX key pattern, use '+' for all, '.' for partial match)",
                    required = true,
                    example = "+"
            )
            @PathVariable(value = "key") String key,
            @Parameter(
                    description = "Filter observations updated after this timestamp (ISO 8601 format)",
                    example = "2024-01-01T00:00:00Z"
            )
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @Parameter(
                    description = "Return only the first N observations",
                    example = "100"
            )
            @RequestParam(value = "firstNObservations", required = false) Integer firstNObservations,
            @Parameter(
                    description = "Return only the last N observations",
                    example = "100"
            )
            @RequestParam(value = "lastNObservations", required = false) Integer lastNObservations,
            @Parameter(
                    description = "Dimension to use for observation-level data. Default: TIME_PERIOD",
                    example = "TIME_PERIOD"
            )
            @RequestParam(value = "dimensionAtObservation", required = false, defaultValue = "TIME_PERIOD") String dimensionAtObservation,
            @Parameter(
                    description = "Attribute inclusion mode. Default: dsd",
                    example = "dsd"
            )
            @RequestParam(value = "attributes", required = false, defaultValue = "dsd") String attributes,
            @Parameter(
                    description = "Measure inclusion mode. Default: all",
                    example = "all"
            )
            @RequestParam(value = "measures", required = false, defaultValue = "all") String measures,
            @Parameter(
                    description = "Include historical revisions. Default: false",
                    example = "false"
            )
            @RequestParam(value = "includeHistory", required = false, defaultValue = "false") String includeHistory,
            @Parameter(
                    description = "Maximum number of observations to return",
                    example = "1000"
            )
            @RequestParam(value = "limit", required = false) Integer limit,
            @Parameter(
                    description = "Return data as it existed at this timestamp (ISO 8601 format)",
                    example = "2024-01-01T00:00:00Z"
            )
            @RequestParam(value = "asOf", required = false) Instant asOf,
            @Parameter(
                    description = "Skip series with no observations. Default: false",
                    example = "false"
            )
            @RequestParam(value = "skipEmptySeries", required = false, defaultValue = "false") boolean skipEmptySeries,
            @Parameter(
                    description = "Additional filter parameters as key-value pairs matching dimension/attribute names",
                    hidden = true
            )
            @RequestParam MultiValueMap<String, String> c,
            @Parameter(
                    description = "Accept header for content negotiation. Supported: application/json, application/vnd.sdmx.data+json;version=1.0.0, application/vnd.sdmx.data+json;version=2.0.0, application/vnd.sdmx.data+xml;version=3.0.0, application/vnd.sdmx.data+csv;version=2.0.0, text/csv, application/csv, application/xml, */* (defaults to application/vnd.sdmx.data+json;version=2.0.0). CSV formats support parameters: labels=[id|name|both], timeFormat=[original|normalized], keys=[none|obs|series|both]. Example: application/csv;labels=both;keys=series"
            )
            @RequestHeader(value = "Accept", required = false) @Nullable String accept,
            @Parameter(description = "URN of the source artefact that contained the cross-reference (for routing context)")
            @RequestHeader(value = "X-Source-Artefact-Urn", required = false) @Nullable String sourceArtefactUrn
    );
}
