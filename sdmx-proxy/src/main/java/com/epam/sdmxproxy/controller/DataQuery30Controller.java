package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.DataQuery30Api;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
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

import static com.epam.sdmxproxy.controller.utils.ControllerUtils.ControllerType.DATA;
import static com.epam.sdmxproxy.controller.utils.ControllerUtils.logRequestUrl;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DataQuery30Controller implements DataQuery30Api {

    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.SDMX_JSON_2_0_0_VALUE;


    @Override
    public ResponseEntity<StreamingResponseBody> dataQuery(
            @PathVariable("context") String context,
            @PathVariable(value = "agencyID") String agencyID,
            @PathVariable(value = "resourceID") String resourceID,
            @PathVariable(value = "version") String version,
            @PathVariable(value = "key") String key,
            @RequestParam(value = "updatedAfter", required = false) Instant updatedAfter,
            @RequestParam(value = "firstNObservations", required = false) Integer firstNObservations,
            @RequestParam(value = "lastNObservations", required = false) Integer lastNObservations,
            @RequestParam(value = "dimensionAtObservation", required = false, defaultValue = "TIME_PERIOD") String dimensionAtObservation,
            @RequestParam(value = "attributes", required = false, defaultValue = "dsd") String attributes,
            @RequestParam(value = "measures", required = false, defaultValue = "all") String measures,
            @RequestParam(value = "includeHistory", required = false, defaultValue = "false") String includeHistory,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "asOf", required = false) Instant asOf,
            @RequestParam(value = "skipEmptySeries", required = false, defaultValue = "false") boolean skipEmptySeries,
            @RequestParam MultiValueMap<String, String> c,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept
    ) {
        logRequestUrl(accept, DATA);

        if (!"dataflow".equals(context)) {
            throw new UnsupportedContextException("context = " + context + " not supported");
        }

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        TranslatedDataQuery translatedDataQuery = queryTranslator.translateDataQuery(
                context,
                agencyID,
                resourceID,
                version,
                key,
                c,
                updatedAfter,
                firstNObservations,
                lastNObservations,
                dimensionAtObservation,
                attributes,
                measures,
                includeHistory,
                limit,
                asOf,
                skipEmptySeries,
                accept,
                getSdmxBeans(agencyID, resourceID, version)
        );

        return ResponseEntity.ok()
                .contentType(translatedDataQuery.getContentType())
                .body(adapterRouter.getData(translatedDataQuery));
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
