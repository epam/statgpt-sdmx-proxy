package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.AgencySchemeApi;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.services.agencyscheme.AgencySchemeService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AgencySchemeController implements AgencySchemeApi {

    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE;

    private final AgencySchemeService agencySchemeService;

    @Override
    public ResponseEntity<StreamingResponseBody> getAgencyScheme(String agencyId, String resourceId, String version, @Nullable String accept) {
        log.debug("GET /structure/agencyscheme/{}/{}/{}", agencyId, resourceId, version);

        if (Strings.CS.equals(accept, SdmxMediaType.ANY) || accept == null) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        MediaType mediaType = MediaType.parseMediaType(accept);

        Optional<byte[]> cached = agencySchemeService.getCachedResponse(mediaType);
        if (cached.isPresent()) {
            byte[] cachedBytes = cached.get();
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(outputStream -> outputStream.write(cachedBytes));
        }

        byte[] response = agencySchemeService.buildAgencySchemeJson();
        agencySchemeService.cacheResponse(mediaType, response);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(outputStream -> outputStream.write(response));
    }
}
