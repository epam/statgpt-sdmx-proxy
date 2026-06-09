package com.epam.sdmxproxy.api;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Agency Discovery", description = "Discover available agencies served by this proxy")
@RequestMapping(value = {WebMvcSettings.API_PREFIX + "/sdmx/3.0/structure"})
public interface AgencySchemeApi {

    @Operation(
            summary = "Get AgencyScheme",
            description = """
                    Returns an SDMX AgencyScheme listing all agencies available through this proxy.
                    Includes configured agencies and dynamically discovered sub-agencies.
                    Use this instead of agency=* wildcard queries.
                    Path variables follow SDMX structure request convention but are ignored -- the proxy
                    always returns its full AgencyScheme regardless of the path parameters.
                    """
    )
    @ApiResponse(responseCode = "200", description = "AgencyScheme retrieved successfully")
    @ApiResponse(responseCode = "500", description = "Internal error during sub-agency discovery")
    @RequestMapping(
            method = RequestMethod.GET,
            value = "/agencyscheme/{agencyId}/{resourceId}/{version}",
            produces = {
                    SdmxMediaTypes.STRUCTURE_XML_2_1,
                    SdmxMediaTypes.STRUCTURE_JSON_2_0_0,
                    SdmxMediaTypeResolver.ANY,
                    MediaType.APPLICATION_JSON_VALUE
            }
    )
    ResponseEntity<StreamingResponseBody> getAgencyScheme(
            @Parameter(description = "Agency ID (ignored, returns all agencies)") @PathVariable("agencyId") String agencyId,
            @Parameter(description = "Resource ID (ignored)") @PathVariable("resourceId") String resourceId,
            @Parameter(description = "Version (ignored)") @PathVariable("version") String version,
            @Parameter(description = "Accept header for content negotiation") @RequestHeader(value = "Accept", required = false) @Nullable String accept
    );
}
