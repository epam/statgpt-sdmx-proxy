package com.epam.sdmxproxy.services.adapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.fixture.data.DataFixtureService;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import feign.FeignException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
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
    private final AvailabilityFixtureService availabilityFixtureService;
    private final DataFixtureService dataFixtureService;

    @Nullable
    private static List<FixtureConfiguration<AvailabilityFixtureType>> getFixtureConfigurations(TranslatedAvailabilityQuery query) {
        AvailabilityEndpointConfiguration endpointConfig = query.getVersionConfiguration().getAvailabilityEndpointConfig();
        return endpointConfig != null ? endpointConfig.getFixtures() : null;
    }

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
        InputStream structures = genericRegistryAdapter.getStructures(query);
        return structures != null
                ? fixtureService.applyFixtures(
                structures,
                query.getRegistryReturnFormat(),
                query.getVersionConfiguration().getStructureEndpointConfig().getFixtures()
        )
                : null;
    }

    private StreamingResponseBody getStructuresConversion(TranslatedStructureQuery query, ReturnFormat returnFormat, MediaType requestedMediaType, String responseKey) {
        return outputStream -> {
            try (InputStream inputStream = getFixedStructureStream(query)) {
                if (inputStream == null) {
                    return;
                }
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
            } catch (FeignException e) {
                log.warn("Failure on registry side.", e);
                throw e;
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
            DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
            SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));
            try (InputStream raw = genericRegistryAdapter.getData(query);
                 InputStream inputStream = dataFixtureService.applyFixtures(
                         raw,
                         returnFormat,
                         sdmxBeans,
                         dataConfig != null ? dataConfig.getFixtures() : null
                 )) {
                streamingDataConversionService.convert(
                        inputStream,
                        outputStream,
                        sdmxBeans,
                        returnFormat,
                        requestedMediaType
                );
            } catch (FeignException e) {
                log.warn("Failure on registry side.", e);
                throw e;
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
        ReturnFormat returnFormat = query.getReturnFormat();

        if (shouldUnwrapStarComponentId(query, versionConfig)) {
            unwrapStarComponentId(query);
        }

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
                throw new IllegalArgumentException("Failed to convert availability data", e);
            }
        };
    }

    private InputStream getFixedAvailabilityStream(TranslatedAvailabilityQuery query) {
        return availabilityFixtureService.applyFixtures(
                genericRegistryAdapter.getAvailability(query),
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


}
