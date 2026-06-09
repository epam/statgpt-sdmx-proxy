package com.epam.sdmxproxy.services.adapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.exception.AvailabilityConversionException;
import com.epam.sdmxproxy.exception.DataConversionException;
import com.epam.sdmxproxy.exception.StructureConversionException;
import com.epam.sdmxproxy.exception.StructureFanOutException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.filter.FilterNormalizer;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.fixture.data.DataFixtureService;
import com.epam.sdmxproxy.services.fixture.data.MetadataAttributesPreserver;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageFolder;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.limit.CachedShrinkResult;
import com.epam.sdmxproxy.services.limit.LimitEmulationService;
import com.epam.sdmxproxy.services.limit.truncate.SeriesLimitTruncator;
import com.epam.sdmxproxy.services.limit.truncate.SeriesLimitTruncatorProvider;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdapterRouterImpl implements AdapterRouter {

    private final StreamingDataConversionService streamingDataConversionService;
    private final StreamingStructureConversionService streamingStructureConversionService;
    private final StreamingAvailabilityConversionService streamingAvailabilityConversionService;
    private final GenericRegistryAdapter genericRegistryAdapter;
    private final QueryTranslator queryTranslator;
    private final CacheService cacheService;
    private final StructureFixtureService fixtureService;
    private final MetadataAttributeUsagePreserver metadataAttributeUsagePreserver;
    private final MetadataAttributeUsageFolder metadataAttributeUsageFolder;
    private final MetadataAttributesPreserver metadataAttributesPreserver;
    private final AvailabilityFixtureService availabilityFixtureService;
    private final DataFixtureService dataFixtureService;
    private final LimitEmulationService limitEmulationService;
    private final SeriesLimitTruncatorProvider truncatorProvider;
    private final FilterNormalizer filterNormalizer;
    private final ObjectMapper objectMapper;

    @Nullable
    private static List<FixtureConfiguration<AvailabilityFixtureType>> getFixtureConfigurations(TranslatedAvailabilityQuery query) {
        AvailabilityEndpointConfiguration endpointConfig = query.getVersionConfiguration().getAvailabilityEndpointConfig();
        return endpointConfig != null ? endpointConfig.getFixtures() : null;
    }

    private static MultiValueMap<String, String> toMultiValueMap(java.util.Map<String, java.util.List<String>> source) {
        LinkedMultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        if (source != null) {
            source.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    result.put(k, new java.util.ArrayList<>(v));
                }
            });
        }
        return result;
    }

    private static java.util.Map<String, java.util.List<String>> toPlainMap(MultiValueMap<String, String> source) {
        java.util.LinkedHashMap<String, java.util.List<String>> result = new java.util.LinkedHashMap<>();
        if (source != null) {
            source.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    result.put(k, new java.util.ArrayList<>(v));
                }
            });
        }
        return result;
    }

    private static InputStream orEmpty(InputStream stream) {
        return stream != null ? stream : InputStream.nullInputStream();
    }

    private static List<String> nonTimeDimensionIds(SdmxBeans beans) {
        DataStructureBean dsd = beans.getDataStructures().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No DataStructure available for filter normalization"));
        return dsd.getDimensionList().getDimensions().stream()
                .filter(d -> !d.isTimeDimension())
                .map(IdentifiableBean::getId)
                .toList();
    }

    @Override
    public StreamingResponseBody getStructures(TranslatedStructureQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        SdmxFormat returnFormat = query.getRegistryReturnFormat();

        String responseKey = CacheKeyGenerator.generateResponseKey(query, requestedMediaType, Collections.emptyMap());

        Optional<byte[]> cachedResponse = cacheService.getReadyResponse(responseKey);
        if (cachedResponse.isPresent()) {
            log.debug("Cache hit for ready response: {}", responseKey);
            return outputStream -> outputStream.write(cachedResponse.get());
        }

        log.debug("Cache miss for ready response: {}", responseKey);

        if (FormatSupportChecker.canBypassStructureFormat(versionConfig, requestedMediaType)) {
            log.debug("Bypassing conversion for structures - bypass enabled and format {} is supported", requestedMediaType);
            return getStructuresBypass(query, responseKey);
        }

        log.debug("Converting structures from {} to {}", returnFormat, requestedMediaType);
        return getStructuresConversion(query, returnFormat, requestedMediaType, responseKey);
    }

    private InputStream getFixedStructureStream(TranslatedStructureQuery query) {
        InputStream structures = genericRegistryAdapter.getStructures(query);
        return structures != null
                ? fixtureService.applyFixtures(
                structures,
                query.getRegistryReturnFormat(),
                query.getVersionConfiguration().getStructureEndpointConfig().getFixtures()
        )
                : null;
    }

    private StreamingResponseBody getStructuresConversion(TranslatedStructureQuery query, SdmxFormat returnFormat, MediaType requestedMediaType, String responseKey) {
        return outputStream -> {
            try (InputStream rawStream = genericRegistryAdapter.getStructures(query)) {
                if (rawStream == null) {
                    return;
                }
                List<FixtureConfiguration<StructureFixtureType>> fixtures = query.getVersionConfiguration().getStructureEndpointConfig().getFixtures();
                boolean markerEnabled = metadataAttributeUsagePreserver.isEnabled(fixtures);
                // JSON output: capture the usages verbatim and re-inject after conversion. XML 2.1 output:
                // fold the usages into the AttributeList as DataAttributes (design 032) -- never for JSON,
                // which would mimic the SDMX-PLUS behaviour removed in design 027.
                boolean preserveUsages = markerEnabled && SdmxMediaTypeResolver.isJson(requestedMediaType);
                boolean foldUsages = markerEnabled && SdmxMediaTypeResolver.isXmlV21(requestedMediaType) && returnFormat == SdmxFormat.JSON_STRUCTURE_2_0_0;

                Map<String, JsonNode> capturedUsages = Map.of();
                InputStream forFixtures;
                if (preserveUsages) {
                    byte[] rawBytes = rawStream.readAllBytes();
                    capturedUsages = metadataAttributeUsagePreserver.capture(rawBytes);
                    forFixtures = new ByteArrayInputStream(rawBytes);
                } else if (foldUsages) {
                    forFixtures = new ByteArrayInputStream(metadataAttributeUsageFolder.foldForXml21(rawStream.readAllBytes(), query));
                } else {
                    forFixtures = rawStream;
                }

                try (InputStream postFixtureStream = fixtureService.applyFixtures(forFixtures, query.getRegistryReturnFormat(), fixtures)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    streamingStructureConversionService.convert(
                            postFixtureStream,
                            buffer,
                            returnFormat,
                            requestedMediaType
                    );

                    byte[] convertedBytes = buffer.toByteArray();
                    if (preserveUsages && !capturedUsages.isEmpty()) {
                        convertedBytes = metadataAttributeUsagePreserver.inject(convertedBytes, capturedUsages);
                    }
                    outputStream.write(convertedBytes);
                    cacheService.putReadyResponse(responseKey, convertedBytes);
                }
            } catch (FeignException e) {
                log.warn("Failure on registry side.", e);
                throw e;
            } catch (Exception e) {
                log.error("Failed to convert structures from {} to {}", returnFormat, requestedMediaType, e);
                throw new StructureConversionException("Failed to convert structures", e);
            }
        };
    }

    @NotNull
    private StreamingResponseBody getStructuresBypass(TranslatedStructureQuery query, String responseKey) {
        return outputStream -> {
            try (InputStream structures = orEmpty(genericRegistryAdapter.getStructures(query))) {
                byte[] bytes = structures.readAllBytes();
                outputStream.write(bytes);
                cacheService.putReadyResponse(responseKey, bytes);
            }
        };
    }

    @Override
    public SdmxBeans getSdmxBeans(TranslatedStructureQuery query) {
        String structureKey = CacheKeyGenerator.generateStructureKey(query);
        byte[] structures = getStructureBytes(query, structureKey);
        if (structures.length == 0) {
            log.debug("Empty structures payload for {}; returning empty SdmxBeans", structureKey);
            return new SdmxBeansImpl();
        }
        SdmxFormat structureReturnFormat = query.getRegistryReturnFormat();
        return streamingStructureConversionService.parseStructures(new ByteArrayInputStream(structures), structureReturnFormat);
    }

    private byte[] getStructureBytes(TranslatedStructureQuery query, String structureKey) {
        Optional<byte[]> cachedStructures = cacheService.getRawStructures(structureKey);
        if (cachedStructures.isPresent()) {
            log.debug("Cache hit for raw structures: {}", structureKey);
            return cachedStructures.get();
        }

        log.debug("Cache miss for raw structures: {}", structureKey);
        InputStream structuresStream = getFixedStructureStream(query);
        if (structuresStream == null) {
            log.debug("Registry returned no body (e.g. HTTP 204) for raw structures: {}", structureKey);
            return new byte[0];
        }
        try (structuresStream) {
            byte[] structures = structuresStream.readAllBytes();
            cacheService.putRawStructures(structureKey, structures);
            return structures;
        } catch (IOException e) {
            throw new StructureConversionException("Failed to read structures", e);
        }
    }

    @Override
    public StreamingResponseBody getStructuresWithFanOut(List<TranslatedStructureQuery> queries, String responseKey) {
        if (queries == null || queries.isEmpty()) {
            throw new UnexpectedStateException("getStructuresWithFanOut called with empty queries");
        }
        MediaType contentType = queries.getFirst().getContentType();
        return outputStream -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(queries.size())) {
                FanOutLegResult legs = runFanOutLegs(queries, executor);
                SdmxBeans merged = mergeFanOutResults(legs.successes());

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                streamingStructureConversionService.convert(merged, buffer, contentType);
                byte[] bytes = buffer.toByteArray();
                outputStream.write(bytes);

                if (legs.failedRegistries().isEmpty()) {
                    cacheService.putReadyResponse(responseKey, bytes);
                } else {
                    log.debug("Fan-out response NOT cached for {} -- {} leg(s) failed: {}", responseKey, legs.failedRegistries().size(), legs.failedRegistries());
                }
            }
        };
    }

    private FanOutLegResult runFanOutLegs(List<TranslatedStructureQuery> queries, ExecutorService executor) {
        List<CompletableFuture<Optional<SdmxBeans>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return Optional.of(getSdmxBeans(q));
                    } catch (Exception e) {
                        log.warn("Fan-out leg failed for registry {}: {}", q.getRegistryConfiguration().getName(), e.getMessage(), e);
                        return Optional.<SdmxBeans>empty();
                    }
                }, executor))
                .toList();

        List<SdmxBeans> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Optional<SdmxBeans> result = futures.get(i).join();
            if (result.isPresent()) {
                ok.add(result.get());
            } else {
                failed.add(queries.get(i).getRegistryConfiguration().getName());
            }
        }
        if (ok.isEmpty()) {
            throw new StructureFanOutException("Structure fan-out failed: every registry leg failed (" + String.join(", ", failed) + ")", failed);
        }
        return new FanOutLegResult(ok, failed);
    }

    private static SdmxBeans mergeFanOutResults(List<SdmxBeans> partials) {
        SdmxBeans merged = new SdmxBeansImpl();
        partials.forEach(merged::merge);
        return merged;
    }

    private record FanOutLegResult(List<SdmxBeans> successes, List<String> failedRegistries) {
    }

    @Override
    public StreamingResponseBody getData(TranslatedDataQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        SdmxFormat returnFormat = query.getReturnFormat();
        DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();

        if (dataConfig != null && dataConfig.isConvertKeyToFilters()) {
            normalizeOutboundFilters(query);
        }

        // BYPASS: if bypass is enabled and requested format is in supportedFormats
        if (FormatSupportChecker.canBypassDataFormat(versionConfig, requestedMediaType)) {
            log.debug("Bypassing conversion for data - bypass enabled and format {} is supported", requestedMediaType);
            return outputStream -> {
                try (InputStream dataInputStream = orEmpty(genericRegistryAdapter.getData(query))) {
                    dataInputStream.transferTo(outputStream);
                }
            };
        }

        // CONVERSION: use returnFormat from query (determined by QueryTranslator) and convert
        log.debug("Converting data from {} to {}", returnFormat, requestedMediaType);
        boolean emulateLimit = query.getLimit() != null
                && dataConfig != null
                && !dataConfig.isSupportsLimit();
        List<FixtureConfiguration<DataFixtureType>> dataFixtures = dataConfig != null ? dataConfig.getFixtures() : null;
        boolean preserveMetadataAttrs = metadataAttributesPreserver.isEnabled(dataFixtures)
                && returnFormat == SdmxFormat.JSON_DATA_2_0_0
                && isJson20Output(requestedMediaType);
        return outputStream -> {
            SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));
            try (InputStream raw = resolveRawDataStream(query, sdmxBeans, emulateLimit);
                 InputStream postFixtures = dataFixtureService.applyFixtures(
                         raw,
                         returnFormat,
                         sdmxBeans,
                         dataFixtures
                 )) {
                if (preserveMetadataAttrs) {
                    // Buffer the upstream bytes to capture sections sdmx-core drops
                    // (MSD-derived metadataAttributeUsages across all four buckets and
                    // their value-side counterparts), then re-inject them after
                    // conversion. See MetadataAttributesPreserver for rationale.
                    byte[] rawBytes = postFixtures.readAllBytes();
                    MetadataAttributesPreserver.Snapshot captured =
                            metadataAttributesPreserver.capture(rawBytes);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    streamingDataConversionService.convert(
                            new ByteArrayInputStream(rawBytes),
                            buffer,
                            sdmxBeans,
                            returnFormat,
                            requestedMediaType
                    );
                    byte[] convertedBytes = metadataAttributesPreserver.inject(buffer.toByteArray(), captured);
                    outputStream.write(convertedBytes);
                } else {
                    streamingDataConversionService.convert(
                            postFixtures,
                            outputStream,
                            sdmxBeans,
                            returnFormat,
                            requestedMediaType
                    );
                }
            } catch (FeignException e) {
                log.warn("Failure on registry side.", e);
                throw e;
            } catch (Exception e) {
                log.error("Failed to convert data from {} to {}", returnFormat, requestedMediaType, e);
                throw new DataConversionException("Failed to convert data", e);
            }
        };
    }

    private static boolean isJson20Output(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        if (!mediaType.getSubtype().toLowerCase().contains("json")) {
            return false;
        }
        String version = mediaType.getParameter("version");
        return version != null && version.startsWith("2.0");
    }

    /**
     * Raw data stream: limit-emulation path when the registry does not honor native
     * {@code limit} (see design 014), otherwise direct passthrough to the registry.
     * In the emulation path this router owns every call to {@code GenericRegistryAdapter}
     * and the final truncation; the shrink service only tells us what query to issue.
     * <p>
     * The shrunk query produced by the bisect is cached per
     * {@code (registry, agency, resource, version, key, filters, limit)} so a repeat
     * request reuses the result without spending availability probes.
     */
    private InputStream resolveRawDataStream(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            boolean emulateLimit
    ) {
        if (!emulateLimit) {
            return orEmpty(genericRegistryAdapter.getData(query));
        }
        int n = query.getLimit() == null ? 0 : query.getLimit();
        SeriesLimitTruncator truncator = truncatorProvider.forFormat(query.getReturnFormat());
        if (n <= 0) {
            log.info("Limit emulation short-circuit: limit={} <= 0, returning empty stream", n);
            return truncator.emptyStream(sdmxBeans);
        }
        TranslatedDataQuery shrunkQuery = resolveShrunkQuery(query, sdmxBeans);
        InputStream raw = orEmpty(genericRegistryAdapter.getData(shrunkQuery));
        return truncator.truncate(raw, n, sdmxBeans);
    }

    /**
     * Returns the shrunk query for {@code query}, hitting the cache when possible.
     * On miss, runs the bisect and writes the result back.
     */
    private TranslatedDataQuery resolveShrunkQuery(TranslatedDataQuery query, SdmxBeans sdmxBeans) {
        String cacheKey = CacheKeyGenerator.generateLimitEmulationKey(query);
        Optional<byte[]> cached = cacheService.getLimitEmulationShrinkFilters(cacheKey);
        if (cached.isPresent()) {
            try {
                CachedShrinkResult result = objectMapper.readValue(cached.get(), CachedShrinkResult.class);
                log.info("Limit emulation cache hit: cacheKey={}, shrunkKey='{}', shrunkFilters={}", cacheKey, result.key(), result.filters());
                return query.toBuilder().key(result.key()).filters(toMultiValueMap(result.filters())).limit(null).build();
            } catch (IOException e) {
                log.warn("Failed to deserialize cached limit emulation entry, recomputing: cacheKey={}", cacheKey, e);
            }
        }

        TranslatedDataQuery shrunkQuery = limitEmulationService.getShrunkQuery(query, sdmxBeans, genericRegistryAdapter::getAvailability);

        try {
            CachedShrinkResult toCache = new CachedShrinkResult(shrunkQuery.getKey(), toPlainMap(shrunkQuery.getFilters()));
            cacheService.putLimitEmulationShrinkFilters(cacheKey, objectMapper.writeValueAsBytes(toCache));
            log.debug("Cached limit emulation shrink: cacheKey={}", cacheKey);
        } catch (IOException e) {
            log.warn("Failed to serialize limit emulation result for caching: cacheKey={}", cacheKey, e);
        }

        return shrunkQuery;
    }

    private TranslatedStructureQuery getStructureQuery(TranslatedDataQuery query) {
        return queryTranslator.translateStructureQuery(
                "dataflow",
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                "descendants",
                "full",
                null,
                null
        );
    }

    private TranslatedStructureQuery getStructureQuery(TranslatedAvailabilityQuery query) {
        return queryTranslator.translateStructureQuery(
                "dataflow",
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                "descendants",
                "full",
                null,
                null
        );
    }

    @Override
    public StreamingResponseBody getAvailability(TranslatedAvailabilityQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        SdmxFormat returnFormat = query.getReturnFormat();
        AvailabilityEndpointConfiguration availabilityConfig = versionConfig.getAvailabilityEndpointConfig();

        if (availabilityConfig != null && availabilityConfig.isConvertKeyToFilters()) {
            normalizeOutboundFilters(query);
        }

        if (shouldUnwrapStarComponentId(query, versionConfig)) {
            unwrapStarComponentId(query);
        }

        // BYPASS: if bypass is enabled and requested format is in supportedFormats
        if (FormatSupportChecker.canBypassAvailabilityFormat(versionConfig, requestedMediaType)) {
            log.debug("Bypassing conversion for availability - bypass enabled and format {} is supported", requestedMediaType);
            return outputStream -> {
                try (InputStream availability = orEmpty(genericRegistryAdapter.getAvailability(query))) {
                    availability.transferTo(outputStream);
                }
            };
        }

        // CONVERSION: use returnFormat from query (determined by QueryTranslator) and convert
        log.debug("Converting availability from {} to {}", returnFormat, requestedMediaType);
        return outputStream -> {
            try (InputStream inputStream = getFixedAvailabilityStream(query)) {
                streamingAvailabilityConversionService.convert(
                        inputStream,
                        outputStream,
                        returnFormat,
                        requestedMediaType
                );
            } catch (FeignException e) {
                log.warn("Failure on registry side.", e);
                throw e;
            } catch (Exception e) {
                log.error("Failed to convert availability from {} to {}", returnFormat, requestedMediaType, e);
                throw new AvailabilityConversionException("Failed to convert availability data", e);
            }
        };
    }

    private InputStream getFixedAvailabilityStream(TranslatedAvailabilityQuery query) {
        return availabilityFixtureService.applyFixtures(
                orEmpty(genericRegistryAdapter.getAvailability(query)),
                query.getReturnFormat(),
                getSdmxBeans(getStructureQuery(query)),
                getFixtureConfigurations(query)
        );
    }

    private void unwrapStarComponentId(TranslatedAvailabilityQuery query) {
        SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));
        DataStructureBean dataStructureBean = sdmxBeans.getDataStructures().stream().findAny().get();
        String unwrappedComponentId = dataStructureBean.getDimensionList().getDimensions()
                .stream()
                .filter(dimensionBean -> !dimensionBean.isTimeDimension())
                .map(IdentifiableBean::getId)
                .collect(Collectors.joining(","));
        query.setComponentId(unwrappedComponentId);
    }

    private boolean shouldUnwrapStarComponentId(TranslatedAvailabilityQuery query, VersionSpecificRegistryConfiguration versionConfig) {
        boolean isAllowedByConfig = versionConfig.getAvailabilityEndpointConfig().isUnwrapStarComponentId();
        boolean isStarComponentId = "*".equals(query.getComponentId()) || query.getComponentId() == null;
        return isAllowedByConfig && isStarComponentId;
    }

    /**
     * Normalize outbound filters: move every dim narrowing into {@code c[]} and emit
     * {@code *} as the path key. Workaround for BIS-style registries -- see design 016.
     */
    private void normalizeOutboundFilters(TranslatedDataQuery query) {
        SdmxBeans beans = getSdmxBeans(getStructureQuery(query));
        FilterNormalizer.NormalizedQuery normalized = filterNormalizer.normalize(query.getKey(), query.getFilters(), nonTimeDimensionIds(beans));
        query.setKey(normalized.key());
        query.setFilters(normalized.filters());
    }

    private void normalizeOutboundFilters(TranslatedAvailabilityQuery query) {
        SdmxBeans beans = getSdmxBeans(getStructureQuery(query));
        FilterNormalizer.NormalizedQuery normalized = filterNormalizer.normalize(query.getKey(), query.getFilters(), nonTimeDimensionIds(beans));
        query.setKey(normalized.key());
        query.setFilters(normalized.filters());
    }

}
