package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.StructureFanOutException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.fixture.FixtureService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import feign.FeignException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final FixtureService fixtureService;

    @Override
    public StreamingResponseBody getStructures(TranslatedStructureQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        ReturnFormat returnFormat = query.getRegistryReturnFormat();

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
        InputStream raw = genericRegistryAdapter.getStructures(query);
        List<FixtureConfiguration> fixtures = query.getVersionConfiguration()
                .getStructureEndpointConfig().getFixtures();
        return fixtureService.applyFixtures(raw, query.getRegistryReturnFormat(), fixtures);
    }

    @NotNull
    private StreamingResponseBody getStructuresConversion(TranslatedStructureQuery query, ReturnFormat returnFormat, MediaType requestedMediaType, String responseKey) {
        return outputStream -> {
            try (InputStream inputStream = getFixedStructureStream(query)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                streamingStructureConversionService.convert(
                        inputStream,
                        buffer,
                        returnFormat,
                        requestedMediaType
                );

                byte[] convertedBytes = buffer.toByteArray();
                outputStream.write(convertedBytes);
                cacheService.putReadyResponse(responseKey, convertedBytes);
            } catch (Exception e) {
                log.error("Failed to convert structures from {} to {}", returnFormat, requestedMediaType, e);
                throw new IllegalArgumentException("Failed to convert structures", e);
            }
        };
    }

    @NotNull
    private StreamingResponseBody getStructuresBypass(TranslatedStructureQuery query, String responseKey) {
        return outputStream -> {
            try (InputStream structures = genericRegistryAdapter.getStructures(query)) {
                byte[] bytes = structures.readAllBytes();
                outputStream.write(bytes);
                cacheService.putReadyResponse(responseKey, bytes);
            }
        };
    }

    @Override
    public SdmxBeans getSdmxBeans(TranslatedStructureQuery query) {
        // Generate structure cache key
        String structureKey = CacheKeyGenerator.generateStructureKey(query);

        byte[] structures = getStructureBytes(query, structureKey);
        ReturnFormat structureReturnFormat = query.getRegistryReturnFormat();
        return streamingStructureConversionService.parseStructures(new ByteArrayInputStream(structures), structureReturnFormat);
    }

    private byte[] getStructureBytes(TranslatedStructureQuery query, String structureKey) {
        Optional<byte[]> cachedStructures = cacheService.getRawStructures(structureKey);
        if (cachedStructures.isPresent()) {
            log.debug("Cache hit for raw structures: {}", structureKey);
            return cachedStructures.get();
        }

        log.debug("Cache miss for raw structures: {}", structureKey);
        byte[] structures;
        try (InputStream structuresStream = getFixedStructureStream(query)) {
            structures = structuresStream.readAllBytes();
            cacheService.putRawStructures(structureKey, structures);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read structures", e);
        }

        return structures;
    }

    @Override
    public StreamingResponseBody getStructuresWithFanOut(List<TranslatedStructureQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("Queries list cannot be null or empty");
        }

        return outputStream -> {
            try (ExecutorService executorService = Executors.newFixedThreadPool(queries.size())) {
                SdmxBeans structuresPerRegistry = getStructuresPerRegistry(queries, executorService);
                streamingStructureConversionService.convert(structuresPerRegistry, outputStream, queries.getFirst().getContentType());
            }
        };
    }

    private SdmxBeans getStructuresPerRegistry(List<TranslatedStructureQuery> queries, ExecutorService executorService) {
        List<CompletableFuture<Optional<SdmxBeans>>> futures = new ArrayList<>();

        for (TranslatedStructureQuery query : queries) {
            futures.add(getStructurePerRegistryFuture(executorService, query));
        }

        return mergeSdmxBeans(collectFanOutResult(queries, futures));
    }

    private List<SdmxBeans> collectFanOutResult(List<TranslatedStructureQuery> queries, List<CompletableFuture<Optional<SdmxBeans>>> futures) {
        List<SdmxBeans> results = new ArrayList<>();
        List<String> failedRegistries = new ArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            TranslatedStructureQuery query = queries.get(i);
            Optional<SdmxBeans> result = futures.get(i).join();

            if (result.isPresent()) {
                results.add(result.get());
            } else {
                String registryName = query.getRegistryConfiguration().getName();
                log.warn("Failed to fetch structures from registry: {}", registryName);
                failedRegistries.add(registryName);
            }
        }

        boolean allRegistriesFailed = results.isEmpty() && !failedRegistries.isEmpty();
        if (allRegistriesFailed) {
            throw new StructureFanOutException(
                    String.format("All registries failed during fan-out: %s", String.join(", ", failedRegistries)),
                    failedRegistries
            );
        }
        return results;
    }

    private CompletableFuture<Optional<SdmxBeans>> getStructurePerRegistryFuture(ExecutorService executorService, TranslatedStructureQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SdmxBeans beans = getSdmxBeans(query);
                return Optional.of(beans);
            } catch (Exception e) {
                log.warn("Failed to fetch structures from registry {}: {}",
                        query.getRegistryConfiguration().getName(), e.getMessage(), e);
                return Optional.empty();
            }
        }, executorService);
    }

    private SdmxBeans mergeSdmxBeans(List<SdmxBeans> sdmxBeans) {
        SdmxBeans results = new SdmxBeansImpl();
        sdmxBeans.forEach(results::merge);
        return results;
    }


    @Override
    public StreamingResponseBody getData(TranslatedDataQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        ReturnFormat returnFormat = query.getReturnFormat();

        // BYPASS: if bypass is enabled and requested format is in supportedFormats
        if (FormatSupportChecker.canBypassDataFormat(versionConfig, requestedMediaType)) {
            log.debug("Bypassing conversion for data - bypass enabled and format {} is supported", requestedMediaType);
            return outputStream -> {
                try (InputStream dataInputStream = genericRegistryAdapter.getData(query)) {
                    dataInputStream.transferTo(outputStream);
                }
            };
        }

        // CONVERSION: use returnFormat from query (determined by QueryTranslator) and convert
        log.debug("Converting data from {} to {}", returnFormat, requestedMediaType);
        return outputStream -> {
            try (InputStream inputStream = genericRegistryAdapter.getData(query)) {
                SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));

                streamingDataConversionService.convert(
                        inputStream,
                        outputStream,
                        sdmxBeans,
                        returnFormat,
                        requestedMediaType
                );
            } catch (Exception e) {
                log.error("Failed to convert data from {} to {}", returnFormat, requestedMediaType, e);
                throw new IllegalArgumentException("Failed to convert data", e);
            }
        };
    }


    private TranslatedStructureQuery getStructureQuery(TranslatedDataQuery query) {
        return queryTranslator.translateStructureQuery(
                "dataflow",
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                "descendants",
                "full",
                null
        );
    }

    @Override
    public StreamingResponseBody getAvailability(TranslatedAvailabilityQuery query) {
        VersionSpecificRegistryConfiguration versionConfig = query.getVersionConfiguration();
        MediaType requestedMediaType = query.getContentType();
        ReturnFormat returnFormat = query.getReturnFormat();

        // BYPASS: if bypass is enabled and requested format is in supportedFormats
        if (FormatSupportChecker.canBypassAvailabilityFormat(versionConfig, requestedMediaType)) {
            log.debug("Bypassing conversion for availability - bypass enabled and format {} is supported", requestedMediaType);
            return outputStream -> {
                try (InputStream availability = genericRegistryAdapter.getAvailability(query)) {
                    availability.transferTo(outputStream);
                }
            };
        }

        // CONVERSION: use returnFormat from query (determined by QueryTranslator) and convert
        log.debug("Converting availability from {} to {}", returnFormat, requestedMediaType);
        return outputStream -> {
            try (InputStream inputStream = genericRegistryAdapter.getAvailability(query)) {
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
                throw new IllegalArgumentException("Failed to convert availability data", e);
            }
        };
    }


}
