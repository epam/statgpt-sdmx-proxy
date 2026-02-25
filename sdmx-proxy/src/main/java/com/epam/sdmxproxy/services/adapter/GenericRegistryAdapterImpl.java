package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30StructureClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenericRegistryAdapterImpl implements GenericRegistryAdapter {

    private final SdmxApiClientProvider sdmxApiClientProvider;

    @Override
    public InputStream getStructures(TranslatedStructureQuery query) {
        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .build();
        Structure structure = query.getStructure();

        switch (selectedRegistry.getVersionConfiguration().getSdmxVersion()) {
            case SDMX_2_1 -> {
                log.debug("Getting SDMX 2.1 structures for {} registry {}:{}:{}({})", query.getRegistryConfiguration().getName(), query.getStructure().type(), query.getStructure().agency(), query.getStructure().id(), query.getStructure().version());
                return getStructures21(query, selectedRegistry, structure);
            }
            case SDMX_3_0 -> {
                log.debug("Getting SDMX 3.0 structures for {} registry {}:{}:{}({})", query.getRegistryConfiguration().getName(), query.getStructure().type(), query.getStructure().agency(), query.getStructure().id(), query.getStructure().version());
                return getStructures30(query, selectedRegistry, structure);
            }
            default ->
                    throw new IllegalStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getStructures30(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry, Structure structure) {
        Sdmx30StructureClient structure30Client = sdmxApiClientProvider.getStructure30Client(selectedRegistry);
        ReturnFormat structureReturnFormat = query.getRegistryReturnFormat();
        return structure30Client.getStructures(
                structureReturnFormat.getContentType(),
                structure.type(),
                structure.agency(),
                structure.id(),
                structure.version(),
                query.getReferences(),
                query.getDetail()
        );
    }

    private InputStream getStructures21(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry, Structure structure) {
        Sdmx21StructureClient structure21Client = sdmxApiClientProvider.getStructure21Client(selectedRegistry);
        ReturnFormat structureReturnFormat = query.getRegistryReturnFormat();
        return structure21Client.getStructures(
                structureReturnFormat.getContentType(),
                structure.type(),
                structure.agency(),
                structure.id(),
                structure.version(),
                query.getReferences(),
                query.getDetail()
        );
    }

    @Override
    public InputStream getData(TranslatedDataQuery query) {
        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .build();

        switch (selectedRegistry.getVersionConfiguration().getSdmxVersion()) {
            case SDMX_2_1 -> {
                return getData21(query, selectedRegistry);
            }
            case SDMX_3_0 -> {
                return getData30(query, selectedRegistry);
            }
            default ->
                    throw new IllegalStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getData21(TranslatedDataQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx21DataClient data21Client = sdmxApiClientProvider.getData21Client(selectedRegistry);
        String flowRef = getFlowRef(query.getAgencyID(), query.getResourceID(), query.getVersion());
        String key = getKey(query);
        String providerRef = "all";
        ReturnFormat dataReturnFormat = query.getReturnFormat();

        //TODO support other query params
        // https://gitlab.deltixhub.com/Deltix/migapp/talk-to-your-data/sdmx-proxy/-/issues/95
        Map<String, Object> dataQueryParams = new HashMap<>();
        if (query.getStartPeriod() != null) {
            dataQueryParams.put("startPeriod", query.getStartPeriod());
        }
        if (query.getEndPeriod() != null) {
            dataQueryParams.put("endPeriod", query.getEndPeriod());
        }
        return data21Client.getData(
                dataReturnFormat.getContentType(),
                flowRef,
                key,
                providerRef,
                dataQueryParams
        );
    }

    private String getKey(TranslatedDataQuery query) {
        return query.getKey() != null ? query.getKey() : "all";
    }

    private String getFlowRef(String agencyId, String resourceId, String version) {
        return agencyId + "," + resourceId + "," + version;
    }

    @NotNull
    private static String wrapKeyIntoC(Map.Entry<String, List<String>> entry) {
        return "c[" + entry.getKey() + "]";
    }

    @Override
    public InputStream getAvailability(TranslatedAvailabilityQuery query) {
        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .build();

        switch (selectedRegistry.getVersionConfiguration().getSdmxVersion()) {
            case SDMX_2_1 -> {
                log.debug("Getting SDMX 2.1 availability for registry: {}, artefact: {}:{}({}), key: {}, componentId: {}", query.getRegistryConfiguration().getName(), query.getAgencyID(), query.getResourceID(), query.getVersion(), query.getKey(), query.getComponentId());
                return getAvailability21(query, selectedRegistry);
            }
            case SDMX_3_0 -> {
                log.debug("Getting SDMX 3.0 availability for registry: {}, artefact: {}:{}({}), key: {}, componentId: {}", query.getRegistryConfiguration().getName(), query.getAgencyID(), query.getResourceID(), query.getVersion(), query.getKey(), query.getComponentId());
                return getAvailability30(query, selectedRegistry);
            }
            default ->
                    throw new IllegalStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getAvailability21(TranslatedAvailabilityQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx21AvailabilityClient availability21Client = sdmxApiClientProvider.getAvailability21Client(selectedRegistry);
        String flowRef = getFlowRef(query.getAgencyID(), query.getResourceID(), query.getVersion());
        String key = query.getKey() != null ? query.getKey() : "all";
        // providerRef is not part of the request - defaulting to "all" for all providers
        String providerRef = "all";
        ReturnFormat availabilityReturnFormat = query.getReturnFormat();
        return availability21Client.getAvailability(
                availabilityReturnFormat.getContentType(),
                flowRef,
                key,
                providerRef,
                query.getComponentId(),
                query.getStartPeriod(),
                query.getEndPeriod(),
                formatInstant(query.getUpdatedAfter()),
                query.getMode(),
                query.getReferences()
        );
    }

    private InputStream getData30(TranslatedDataQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx30DataClient data30Client = sdmxApiClientProvider.getData30Client(selectedRegistry);
        String context = query.getContext() != null ? query.getContext() : "dataflow"; // Default to dataflow if not provided
        ReturnFormat dataReturnFormat = query.getReturnFormat();
        Map<String, String> filters = convertFiltersToMap(query.getFilters());

        return data30Client.getData(
                dataReturnFormat.getContentType(),
                context,
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                query.getKey() != null ? query.getKey() : "all",
                formatInstant(query.getUpdatedAfter()),
                query.getFirstNObservations(),
                query.getLastNObservations(),
                query.getDimensionAtObservation(),
                query.getAttributes(),
                query.getMeasures(),
                query.getIncludeHistory(),
                query.getLimit(),
                formatInstant(query.getAsOf()),
                query.isSkipEmptySeries(),
                filters
        );
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ISO_DATE_TIME.format(instant);
    }

    private InputStream getAvailability30(TranslatedAvailabilityQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx30AvailabilityClient availability30Client = sdmxApiClientProvider.getAvailability30Client(selectedRegistry);
        String context = query.getContext() != null ? query.getContext() : "dataflow"; // Default to dataflow if not provided
        Map<String, String> filters = convertFiltersToMap(query.getFilters());

        ReturnFormat availabilityReturnFormat = query.getReturnFormat();
        return availability30Client.getAvailability(
                availabilityReturnFormat.getContentType(),
                context,
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                query.getKey() != null ? query.getKey() : "*",
                query.getComponentId() != null ? query.getComponentId() : "*",
                filters,
                formatInstant(query.getUpdatedAfter()),
                query.getMode(),
                query.getReferences(),
                query.getReportingYearStartDay()
        );
    }

    private Map<String, String> convertFiltersToMap(MultiValueMap<String, String> filters) {
        Map<String, String> result = new HashMap<>();
        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    String wrappedKey = wrapKeyIntoC(entry);
                    result.put(wrappedKey, values.get(0));
                }
            }
        }
        return result;
    }
}
