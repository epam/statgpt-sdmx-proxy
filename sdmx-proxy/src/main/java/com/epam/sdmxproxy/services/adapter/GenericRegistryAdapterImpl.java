package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30StructureClient;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
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
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;

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
                    throw new UnexpectedStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getStructures30(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry, Structure structure) {
        Sdmx30StructureClient structure30Client = sdmxApiClientProvider.getStructure30Client(selectedRegistry);
        SdmxFormat structureReturnFormat = query.getRegistryReturnFormat();
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

    /**
     * The 2.1 structure path is built from raw SDMX 3.0 slots, whose documented default is {@code *}.
     * Most callers pre-map the id and version slots, but agency-scheme discovery sends a literal
     * {@code *} agency, so every slot goes through the normalizer here -- the structure counterpart
     * of the {@link #getFlowRef} choke point.
     */
    private InputStream getStructures21(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry, Structure structure) {
        Sdmx21StructureClient structure21Client = sdmxApiClientProvider.getStructure21Client(selectedRegistry);
        SdmxFormat structureReturnFormat = query.getRegistryReturnFormat();
        return structure21Client.getStructures(
                structureReturnFormat.getContentType(),
                structure.type(),
                sdmx21QueryNormalizer.toSdmx21PathSlot(structure.agency()),
                sdmx21QueryNormalizer.toSdmx21PathSlot(structure.id()),
                sdmx21QueryNormalizer.toSdmx21PathSlot(structure.version()),
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
                    throw new UnexpectedStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getData21(TranslatedDataQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx21DataClient data21Client = sdmxApiClientProvider.getData21Client(selectedRegistry);
        String flowRef = getFlowRef(query.getAgencyID(), query.getResourceID(), query.getVersion());
        String key = sdmx21QueryNormalizer.toKey(query.getKey());
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

    /**
     * The 2.1 flowRef is built from raw SDMX 3.0 path slots, whose documented default is {@code *}.
     * A 2.1 registry needs {@code all} instead, so every slot goes through the normalizer here --
     * this is the single choke point shared by the 2.1 data and availability calls.
     */
    private String getFlowRef(String agencyId, String resourceId, String version) {
        return sdmx21QueryNormalizer.toSdmx21PathSlot(agencyId) + "," + sdmx21QueryNormalizer.toSdmx21PathSlot(resourceId) + "," + sdmx21QueryNormalizer.toSdmx21PathSlot(version);
    }

    /**
     * For CSV return format, builds the Accept header from the registry's base CSV content type
     * with the client's CSV parameters (labels, keys, timeFormat) appended.
     * For other formats, uses the static content type from SdmxFormat.
     */
    private String resolveDataAcceptHeader(TranslatedDataQuery query) {
        SdmxFormat returnFormat = query.getReturnFormat();
        if (returnFormat == SdmxFormat.CSV_DATA_1_0_0 || returnFormat == SdmxFormat.CSV_DATA_2_0_0) {
            return buildCsvAcceptHeader(returnFormat, query.getContentType());
        }
        return returnFormat.getContentType();
    }

    private String buildCsvAcceptHeader(SdmxFormat returnFormat, MediaType clientMediaType) {
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
                    throw new UnexpectedStateException("Unexpected value: " + selectedRegistry.getVersionConfiguration().getSdmxVersion());
        }
    }

    private InputStream getAvailability21(TranslatedAvailabilityQuery query, RegistrySelectionResult selectedRegistry) {
        Sdmx21AvailabilityClient availability21Client = sdmxApiClientProvider.getAvailability21Client(selectedRegistry);
        String flowRef = getFlowRef(query.getAgencyID(), query.getResourceID(), query.getVersion());
        String key = sdmx21QueryNormalizer.toKey(query.getKey());
        // providerRef is not part of the request - defaulting to "all" for all providers
        String providerRef = "all";
        String componentId = sdmx21QueryNormalizer.toComponentId(query.getComponentId());
        // null drops the parameter from the query string; NSI answers references=none with a 500.
        String references = sdmx21QueryNormalizer.toReferences(query.getReferences());
        SdmxFormat availabilityReturnFormat = query.getReturnFormat();
        return availability21Client.getAvailability(
                availabilityReturnFormat.getContentType(),
                flowRef,
                key,
                providerRef,
                componentId,
                query.getStartPeriod(),
                query.getEndPeriod(),
                formatInstant(query.getUpdatedAfter()),
                query.getMode(),
                references
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
        return joinPerComponent(filters, key -> "c[" + key + "]");
    }

    /**
     * Builds a {@link MultiValueMap} where each Component id maps to at most one value --
     * multiple values per Component are OR-joined with ',' as required by SDMX-REST 2.2.0
     * ({@code sdmx-rest-2.2.0/doc/data.md}): {@code c[X]} may appear at most once per
     * Component; the spec-compliant forms are {@code c[X]=A,B} (OR) and
     * {@code c[X]=ge:A+le:B} (AND). Emitting repeated {@code c[X]} (or, on
     * {@code unwrapFilterParameters=true} endpoints, repeated bare {@code X}) is a spec
     * violation: BIS silently keeps only the first occurrence and drops the rest.
     */
    private static MultiValueMap<String, String> joinPerComponent(
            MultiValueMap<String, String> filters,
            java.util.function.UnaryOperator<String> keyTransform
    ) {
        MultiValueMap<String, String> out = new LinkedMultiValueMap<>();
        if (MapUtils.isEmpty(filters)) {
            return out;
        }
        filters.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                out.add(keyTransform.apply(key), String.join(",", values));
            }
        });
        return out;
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
        SdmxFormat availabilityReturnFormat = query.getReturnFormat();
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
            // Unwrap: bare `X=A,B` (no c[] prefix). Must still comma-join per Component --
            // the repeated-param spec violation applies equally to this shape.
            return joinPerComponent(filters, java.util.function.UnaryOperator.identity());
        }
        return wrapIntoC(filters);
    }

}
