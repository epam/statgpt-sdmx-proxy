package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.AvailabilityQuery30Api;
import com.epam.sdmxproxy.common.data.AvailabilityQueryRequestDto;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FilterUtils;
import com.epam.sdmxproxy.exception.UnsupportedContextException;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;

import static com.epam.sdmxproxy.controller.utils.ControllerUtils.ControllerType.AVAILABILITY;
import static com.epam.sdmxproxy.controller.utils.ControllerUtils.logRequestUrl;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AvailabilityQuery30Controller implements AvailabilityQuery30Api {

    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE;


    @Override
    public ResponseEntity<StreamingResponseBody> availabilityQuery(
            @PathVariable("context") String context,
            @PathVariable(value = "agencyID") String agencyID,
            @PathVariable(value = "resourceID") String resourceID,
            @PathVariable(value = "version") String version,
            @PathVariable(value = "key") String key,
            @PathVariable(value = "componentId") String componentId,
            @RequestParam MultiValueMap<String, String> c,
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @RequestParam(value = "mode", required = false, defaultValue = "exact") String mode,
            @RequestParam(value = "references", required = false, defaultValue = "none") String references,
            @RequestParam(value = "reportingYearStartDay", required = false) String reportingYearStartDay,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept
    ) {
        logRequestUrl(accept, AVAILABILITY);

        if (!"dataflow".equals(context)) {
            throw new UnsupportedContextException("context = " + context + " not supported");
        }

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        TranslatedAvailabilityQuery translatedQuery = queryTranslator.translateAvailabilityQuery(
                context,
                agencyID,
                resourceID,
                version,
                key,
                componentId,
                c,
                updatedAfter,
                mode,
                references,
                null, // startPeriod - not in SDMX 3.0 API
                null, // endPeriod - not in SDMX 3.0 API
                reportingYearStartDay,
                accept,
                getSdmxBeans(agencyID, resourceID, version)
        );

        return ResponseEntity.ok()
                .contentType(translatedQuery.getContentType())
                .body(adapterRouter.getAvailability(translatedQuery));
    }

    @Override
    public ResponseEntity<StreamingResponseBody> availabilityQueryPost(
            @PathVariable("context") String context,
            @PathVariable(value = "agencyID") String agencyID,
            @PathVariable(value = "resourceID") String resourceID,
            @PathVariable(value = "version") String version,
            @org.springframework.web.bind.annotation.RequestBody AvailabilityQueryRequestDto request,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept
    ) {
        if (!"dataflow".equals(context)) {
            throw new UnsupportedContextException("context = " + context + " not supported");
        }

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        // Convert DTO filters to MultiValueMap format
        MultiValueMap<String, String> c = FilterUtils.convertFiltersToMultiValueMap(request.getFilter());

        TranslatedAvailabilityQuery translatedQuery = queryTranslator.translateAvailabilityQuery(
                context,
                agencyID,
                resourceID,
                version,
                request.getKey(),
                request.getComponentId(),
                c,
                request.getUpdatedAfter(),
                request.getMode() != null ? request.getMode() : "exact",
                request.getReferences() != null ? request.getReferences() : "none",
                null, // startPeriod - not in SDMX 3.0 API
                null, // endPeriod - not in SDMX 3.0 API
                request.getReportingYearStartDay(),
                accept,
                getSdmxBeans(agencyID, resourceID, version)
        );

        return ResponseEntity.ok()
                .contentType(translatedQuery.getContentType())
                .body(adapterRouter.getAvailability(translatedQuery));
    }

    private SdmxBeans getSdmxBeans(String agencyID, String resourceID, String version) {
        TranslatedStructureQuery structureQuery = queryTranslator.translateStructureQuery(
                "dataflow",
                agencyID,
                resourceID,
                version,
                "descendants",
                "full",
                null
        );
        return adapterRouter.getSdmxBeans(structureQuery);
    }
}
