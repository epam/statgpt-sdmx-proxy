package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
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
import org.apache.commons.collections4.MapUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
        String acceptHeader = resolveDataAcceptHeader(query);

        //TODO support other query params
        Map<String, Object> dataQueryParams = new HashMap<>();
        if (query.getStartPeriod() != null) {
            dataQueryParams.put("startPeriod", query.getStartPeriod());
        }
        if (query.getEndPeriod() != null) {
            dataQueryParams.put("endPeriod", query.getEndPeriod());
        }
        return data21Client.getData(
                acceptHeader,
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

    /**
     * For CSV return format, builds the Accept header from the registry's base CSV content type
     * with the client's CSV parameters (labels, keys, timeFormat) appended.
     * For other formats, uses the static content type from ReturnFormat.
     */
    private String resolveDataAcceptHeader(TranslatedDataQuery query) {
        ReturnFormat returnFormat = query.getReturnFormat();
        if (returnFormat == ReturnFormat.CSV_DATA_1_0_0 || returnFormat == ReturnFormat.CSV_DATA_2_0_0) {
            return buildCsvAcceptHeader(returnFormat, query.getContentType());
        }
        return returnFormat.getContentType();
    }

    private String buildCsvAcceptHeader(ReturnFormat returnFormat, MediaType clientMediaType) {
        StringBuilder header = new StringBuilder(returnFormat.getContentType());
        appendCsvParam(header, clientMediaType, "labels");
        appendCsvParam(header, clientMediaType, "timeFormat");
        appendCsvParam(header, clientMediaType, "keys");
        return header.toString();
    }

    private void appendCsvParam(StringBuilder header, MediaType mediaType, String paramName) {
        String value = mediaType.getParameter(paramName);
        if (value != null) {
            header.append(";").append(paramName).append("=").append(value);
        }
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
        String acceptHeader = resolveDataAcceptHeader(query);

        return data30Client.getData(
                acceptHeader,
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
                wrapIntoC(query.getFilters())
        );
    }

    private MultiValueMap<String, String> wrapIntoC(MultiValueMap<String, String> filters) {
        MultiValueMap<String, String> wrapped = new LinkedMultiValueMap<>();
        if (MapUtils.isEmpty(filters)) {
            return wrapped;
        }
        filters.forEach((key, values) -> wrapped.addAll("c[" + key + "]", values));
        return wrapped;
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
        ReturnFormat availabilityReturnFormat = query.getReturnFormat();
        return availability30Client.getAvailability(
                availabilityReturnFormat.getContentType(),
                context,
                query.getAgencyID(),
                query.getResourceID(),
                query.getVersion(),
                query.getKey() != null ? query.getKey() : "*",
                query.getComponentId() != null ? query.getComponentId() : "*",
                resolveAvailabilityFilters(query.getFilters(), selectedRegistry),
                formatInstant(query.getUpdatedAfter()),
                query.getMode(),
                query.getReferences(),
                query.getReportingYearStartDay()
        );
    }

    private MultiValueMap<String, String> resolveAvailabilityFilters(MultiValueMap<String, String> filters,
                                                                      RegistrySelectionResult selectedRegistry) {
        AvailabilityEndpointConfiguration availabilityConfig = selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig();
        if (availabilityConfig != null && availabilityConfig.isUnwrapFilterParameters()) {
            return filters != null ? filters : new LinkedMultiValueMap<>();
        }
        return wrapIntoC(filters);
    }

}
