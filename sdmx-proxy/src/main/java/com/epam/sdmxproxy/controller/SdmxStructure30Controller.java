package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.api.SdmxStructure30Api;
import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Optional;

import static com.epam.sdmxproxy.common.data.SdmxMediaType.parseMediaType;
import static com.epam.sdmxproxy.controller.utils.ControllerUtils.ControllerType.STRUCTURE;
import static com.epam.sdmxproxy.controller.utils.ControllerUtils.logRequestUrl;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SdmxStructure30Controller implements SdmxStructure30Api {

    private static final String WILDCARD_AGENCY = "*";
    private static final String ACCEPT_HEADER_FALLBACK = SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE;

    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private final ProxyConfigurationProvider configurationProvider;
    private final CacheService cacheService;

    @Override
    public ResponseEntity<StreamingResponseBody> getResources(
            @PathVariable("structureType") String structureType,
            @PathVariable("agencyId") String agencyId,
            @PathVariable("resourceId") String resourceId,
            @PathVariable("version") String version,
            @RequestParam(value = "references", required = false) @Nullable String references,
            @RequestParam(value = "detail", required = false, defaultValue = "full") String detail,
            @RequestHeader(value = "Accept", required = false) @Nullable String accept,
            @RequestHeader(value = "X-Source-Artefact-Urn", required = false) @Nullable String sourceArtefactUrn
    ) {
        logRequestUrl(accept, STRUCTURE);

        if (Strings.CS.equals(accept, SdmxMediaType.ANY)) {
            accept = ACCEPT_HEADER_FALLBACK;
        }

        agencyId = queryTranslator.normalizePathSlot(agencyId);
        resourceId = queryTranslator.normalizePathSlot(resourceId);
        version = queryTranslator.normalizePathSlot(version);

        ProxyConfiguration config = configurationProvider.getConfiguration();
        if (WILDCARD_AGENCY.equals(agencyId) && config.isStructureFanOutEnabled()) {
            return fanOutResponse(structureType, resourceId, version, references, detail, accept, config);
        }

        TranslatedStructureQuery structureQuery = queryTranslator.translateStructureQuery(
                structureType, agencyId, resourceId, version, references, detail, accept, sourceArtefactUrn
        );

        return ResponseEntity.ok()
                .contentType(structureQuery.getContentType())
                .body(adapterRouter.getStructures(structureQuery));
    }

    private ResponseEntity<StreamingResponseBody> fanOutResponse(
            String structureType,
            String resourceId,
            String version,
            @Nullable String references,
            String detail,
            @Nullable String accept,
            ProxyConfiguration config
    ) {
        MediaType contentType = parseMediaType(accept).getMediaType();
        int configsHash = config.getConfigs() != null ? config.getConfigs().hashCode() : 0;
        String cacheKey = CacheKeyGenerator.generateFanOutResponseKey(
                structureType, resourceId, version, references, detail, contentType, configsHash
        );

        Optional<byte[]> cached = cacheService.getReadyResponse(cacheKey);
        if (cached.isPresent()) {
            log.debug("Fan-out cache hit: {}", cacheKey);
            byte[] body = cached.get();
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(outputStream -> outputStream.write(body));
        }
        log.debug("Fan-out cache miss: {}", cacheKey);

        List<TranslatedStructureQuery> queries = queryTranslator.translateWildcardStructureFanOut(
                structureType, resourceId, version, references, detail, accept
        );
        if (queries.isEmpty()) {
            log.debug("Fan-out produced no queries for type {} -- no configured registry supports it", structureType);
            return ResponseEntity.ok().contentType(contentType).body(outputStream -> {
            });
        }
        return ResponseEntity.ok()
                .contentType(queries.getFirst().getContentType())
                .body(adapterRouter.getStructuresWithFanOut(queries, cacheKey));
    }
}
