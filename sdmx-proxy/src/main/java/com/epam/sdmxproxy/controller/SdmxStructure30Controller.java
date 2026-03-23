package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.SdmxStructure30Api;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

import static com.epam.sdmxproxy.controller.utils.ControllerUtils.ControllerType.STRUCTURE;
import static com.epam.sdmxproxy.controller.utils.ControllerUtils.logRequestUrl;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SdmxStructure30Controller implements SdmxStructure30Api {

    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE;



    @Override
    public ResponseEntity<StreamingResponseBody> getResources(
            @PathVariable("structureType") String structureType,
            @PathVariable("agencyId") String agencyId,
            @PathVariable("resourceId") String resourceId,
            @PathVariable("version") String version,
            @RequestParam(value = "references", required = false) @Nullable String references,
            @RequestParam(value = "detail", required = false, defaultValue = "full") String detail,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept
    ) {
        logRequestUrl(accept, STRUCTURE);

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        if (queryTranslator.requiresFanOut(agencyId)) {
            return getStructuresWithFanOut(structureType, agencyId, resourceId, version, references, detail, accept);
        }

        return getStructures(structureType, agencyId, resourceId, version, references, detail, accept);
    }

    private ResponseEntity<StreamingResponseBody> getStructures(String structureType, String agencyId, String resourceId, String version, @org.jetbrains.annotations.Nullable String references, String detail, @org.jetbrains.annotations.Nullable String accept) {
        TranslatedStructureQuery structureQuery = queryTranslator.translateStructureQuery(
                structureType, agencyId, resourceId, version, references, detail, accept
        );

        return ResponseEntity.ok()
                .contentType(structureQuery.getContentType())
                .body(adapterRouter.getStructures(structureQuery));
    }

    private ResponseEntity<StreamingResponseBody> getStructuresWithFanOut(String structureType, String agencyId, String resourceId, String version, @org.jetbrains.annotations.Nullable String references, String detail, @org.jetbrains.annotations.Nullable String accept) {
        List<TranslatedStructureQuery> queries = queryTranslator.translateToFanOutStructures(
                structureType, agencyId, resourceId, version, references, detail, accept
        );

        return ResponseEntity.ok()
                .contentType(queries.getFirst().getContentType())
                .body(adapterRouter.getStructuresWithFanOut(queries));
    }
}
