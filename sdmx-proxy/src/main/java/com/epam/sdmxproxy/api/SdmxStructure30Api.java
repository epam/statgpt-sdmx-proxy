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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "SDMX Structure", description = "SDMX structure queries (datastructure, dataflow, codelist, etc.)")
@RequestMapping(value = {WebMvcSettings.API_PREFIX + "/sdmx/3.0/structure"})
public interface SdmxStructure30Api {

    @Operation(
            summary = "Get SDMX structure",
            description = """
                    Retrieves SDMX structure resources (datastructure, dataflow, codelist, etc.) from configured registries.
                    
                    Supports content negotiation via Accept header:
                    - application/vnd.sdmx.structure+xml;version=2.1
                    - application/vnd.sdmx.structure+json;version=2.0.0 (default)
                    - */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)
                    
                    **Fan-Out Support**: If the agencyId is "*" (wildcard) or contains comma-separated agencies that map to different registries, 
                    the query will be executed against multiple registries and results will be merged.
                    
                    **Path Variables**:
                    - structureType: Type of structure (datastructure, dataflow, codelist, etc.)
                    - agencyId: Maintenance agency ID (e.g., "BIS", "IMF", "*" for all, or comma-separated list)
                    - resourceId: Resource identifier
                    - version: Version identifier (e.g., "1.0", "latest")
                    
                    **Query Parameters**:
                    - references: Controls which referenced structures to include (none, parents, children, descendants, all)
                    - detail: Level of detail (full, referenceonly, allstubs, referencestubs)
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Structure retrieved successfully",
                    content = {
                            @Content(
                                    mediaType = SdmxMediaType.STRUCTURE_SDMX_XML_2_1_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                                    schema = @Schema(type = "string", format = "binary")
                            )
                    }
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Bad request - invalid parameters or unsupported structure type"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Service unavailable - registry is down or circuit breaker is open"
            )
    })
    @RequestMapping(
            method = RequestMethod.GET,
            value = "/{structureType}/{agencyId}/{resourceId}/{version}",
            produces = {
                    SdmxMediaType.STRUCTURE_SDMX_XML_2_1_VALUE,
                    SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE,
                    SdmxMediaType.ANY
            }
    )
    ResponseEntity<StreamingResponseBody> getResources(
            @Parameter(
                    description = "Type of structure to retrieve (e.g., datastructure, dataflow, codelist)",
                    required = true,
                    example = "dataflow"
            )
            @PathVariable("structureType") String structureType,
            @Parameter(
                    description = "Maintenance agency ID (e.g., 'BIS', 'IMF', '*' for all agencies, or comma-separated list like 'BIS,IMF')",
                    required = true,
                    example = "BIS"
            )
            @PathVariable("agencyId") String agencyId,
            @Parameter(
                    description = "Resource identifier",
                    required = true,
                    example = "WS_CBS_PUB"
            )
            @PathVariable("resourceId") String resourceId,
            @Parameter(
                    description = "Version identifier (e.g., '1.0', 'latest')",
                    required = true,
                    example = "1.0"
            )
            @PathVariable("version") String version,
            @Parameter(
                    description = "Controls which referenced structures to include. Options: none, parents, children, descendants, all",
                    example = "descendants"
            )
            @RequestParam(value = "references", required = false) @Nullable String references,
            @Parameter(
                    description = "Level of detail. Options: full, referenceonly, allstubs, referencestubs. Default: full",
                    example = "full"
            )
            @RequestParam(value = "detail", required = false, defaultValue = "full") String detail,
            @Parameter(
                    description = "Accept header for content negotiation. Supported: application/vnd.sdmx.structure+xml;version=2.1, application/vnd.sdmx.structure+json;version=2.0.0, */* (defaults to application/vnd.sdmx.structure+json;version=2.0.0)"
            )
            @RequestHeader(value = "Accept", required = false) @Nullable String accept
    );
}
