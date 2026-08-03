package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.common.data.MediaTypeParseResult;
import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.FilterValidationException;
import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException;
import com.epam.sdmxproxy.exception.UnsupportedContextException;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.filter.FilterTranslator;
import com.epam.sdmxproxy.services.filter.FilterValidator;
import com.epam.sdmxproxy.services.misc.DimensionService;
import com.epam.sdmxproxy.services.routing.AgencyRoutingService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver.parseMediaType;


@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTranslatorImpl implements QueryTranslator {
    public static final String SDMX_30_ALL_WILDCARD = "*";
    public static final String SDMX_21_ALL_WILDCARD = "all";
    private static final String AND_SEPARATOR_REGEX = "\\+";
    private static final String AND_SEPARATOR = "+";
    private static final String OPERATOR_VALUE_SEPARATOR = ":";
    private static final Set<String> START_OPERATORS = Set.of("ge", "gt");
    private static final Set<String> END_OPERATORS = Set.of("le", "lt");
    public static final String AGENCY_SCHEMA = "agencyschema";
    private final AgencyRoutingService agencyRoutingService;
    private final FilterValidator filterValidationService;
    private final FilterTranslator filterTranslator;
    private final DimensionService dimensionService;
    private final ProxyConfigurationProvider configurationProvider;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;

    private static String getVersionSpecificQueryId(String id, VersionSpecificRegistryConfiguration versionConfig) {
        String queryId = id;
        if (queryId.equals(SDMX_30_ALL_WILDCARD)) {
            queryId = versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1 ? SDMX_21_ALL_WILDCARD : SDMX_30_ALL_WILDCARD;
        }
        return queryId;
    }

    @Override
    public String normalizePathSlot(String slot) {
        return SDMX_21_ALL_WILDCARD.equals(slot) ? SDMX_30_ALL_WILDCARD : slot;
    }

    private static MultiValueMap<String, String> copyFiltersWithout(MultiValueMap<String, String> filters, String excludeKey) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            if (!entry.getKey().equals(excludeKey)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String[] deriveStartEndFromTimeFilterValue(String value) {
        String startPeriod = null;
        String endPeriod = null;
        if (value == null || value.isBlank()) {
            return new String[]{null, null};
        }
        List<String> startValues = new ArrayList<>();
        List<String> endValues = new ArrayList<>();
        for (String part : value.split(AND_SEPARATOR_REGEX)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(OPERATOR_VALUE_SEPARATOR);
            String op = colon > 0 ? trimmed.substring(0, colon) : "eq";
            String val = colon > 0 && colon + 1 < trimmed.length() ? trimmed.substring(colon + 1).trim() : trimmed;
            if (val.isEmpty()) {
                continue;
            }
            if (START_OPERATORS.contains(op)) {
                startValues.add(val);
            } else if (END_OPERATORS.contains(op)) {
                endValues.add(val);
            }
        }
        if (!startValues.isEmpty()) {
            startPeriod = startValues.stream().max(String::compareTo).orElse(null);
        }
        if (!endValues.isEmpty()) {
            endPeriod = endValues.stream().min(String::compareTo).orElse(null);
        }
        return new String[]{startPeriod, endPeriod};
    }

    /**
     * Selects registry and version configuration for a given agency and desired SDMX version.
     * Uses AgencyRoutingService for registry resolution, then version fallback logic.
     */
    private RegistrySelectionResult selectRegistryAndVersion(String agencyID, SdmxVersion desiredVersion, @Nullable String sourceArtefactUrn) {
        RegistryConfiguration registryConfig = agencyRoutingService.resolveRegistry(agencyID, sourceArtefactUrn);

        // Try exact version match first
        if (desiredVersion != null) {
            VersionSpecificRegistryConfiguration versionConfig = registryConfig.getVersionConfiguration(desiredVersion);
            if (versionConfig != null) {
                return RegistrySelectionResult.builder()
                        .registryConfiguration(registryConfig)
                        .versionConfiguration(versionConfig)
                        .build();
            }
        }

        // Fallback: prefer 3.0, then 2.1
        VersionSpecificRegistryConfiguration version30 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0);
        if (version30 != null) {
            return RegistrySelectionResult.builder()
                    .registryConfiguration(registryConfig)
                    .versionConfiguration(version30)
                    .build();
        }

        VersionSpecificRegistryConfiguration version21 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_2_1);
        if (version21 != null) {
            return RegistrySelectionResult.builder()
                    .registryConfiguration(registryConfig)
                    .versionConfiguration(version21)
                    .build();
        }

        throw new IllegalRegistryConfigurationException(String.format("No suitable SDMX version found for registry: %s, agency: %s", registryConfig.getName(), agencyID));
    }

    @Override
    public TranslatedStructureQuery translateStructureQuery(
            String structureType,
            String agencyId,
            String resourceId,
            String version,
            String references,
            String detail,
            String acceptHeader,
            String sourceArtefactUrn
    ) {
        agencyId = normalizePathSlot(agencyId);
        resourceId = normalizePathSlot(resourceId);
        version = normalizePathSlot(version);

        if ("*".equals(agencyId) || (agencyId != null && agencyId.contains(","))) {
            throw new UnsupportedAgencyWildcardException("Wildcard and comma-separated agency queries are not supported. Use /structure/agencyscheme to discover agencies.");
        }

        MediaTypeParseResult parsedMediaType = parseMediaType(acceptHeader);

        RegistrySelectionResult selectedRegistry = selectRegistryAndVersion(agencyId, parsedMediaType.getSdmxVersion(), sourceArtefactUrn);

        SdmxFormat returnFormat = determineStructureReturnFormat(
                selectedRegistry,
                parsedMediaType
        );

        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        return TranslatedStructureQuery.builder()
                .registryConfiguration(selectedRegistry.getRegistryConfiguration())
                .versionConfiguration(versionConfig)
                .structure(getStructure(versionConfig, structureType, agencyId, getVersionSpecificQueryId(resourceId, versionConfig), getVersionSpecificQueryId(version, versionConfig)))
                .references(references)
                .detail(detail)
                .contentType(parsedMediaType.getMediaType())
                .registryReturnFormat(returnFormat)
                .build();

    }

    @Override
    public TranslatedStructureQuery translateStructureQueryForAgencySchemaDiscovery(String agencyId) {
        MediaTypeParseResult parsedMediaType = parseMediaType(null);

        RegistrySelectionResult selectedRegistry = selectRegistryAndVersion(agencyId, parsedMediaType.getSdmxVersion(), null);

        SdmxFormat returnFormat = determineStructureReturnFormat(
                selectedRegistry,
                parsedMediaType
        );

        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        return TranslatedStructureQuery.builder()
                .registryConfiguration(selectedRegistry.getRegistryConfiguration())
                .versionConfiguration(versionConfig)
                .structure(getStructure(versionConfig, "dataflow", "*", getVersionSpecificQueryId("*", versionConfig), getVersionSpecificQueryId("*", versionConfig)))
                .references("children")
                .detail("full")
                .contentType(parsedMediaType.getMediaType())
                .registryReturnFormat(returnFormat)
                .build();

    }

    @Override
    public List<TranslatedStructureQuery> translateWildcardStructureFanOut(
            String structureType,
            String resourceId,
            String version,
            String references,
            String detail,
            String acceptHeader
    ) {
        resourceId = normalizePathSlot(resourceId);
        version = normalizePathSlot(version);

        MediaTypeParseResult parsedMediaType = parseMediaType(acceptHeader);
        ProxyConfiguration configuration = configurationProvider.getConfiguration();
        List<RegistryConfiguration> registries = configuration.getConfigs();
        if (registries == null || registries.isEmpty()) {
            return List.of();
        }

        List<TranslatedStructureQuery> queries = new ArrayList<>();
        for (RegistryConfiguration registryConfig : registries) {
            VersionSpecificRegistryConfiguration versionConfig = selectVersionForStructureType(registryConfig, structureType, parsedMediaType.getSdmxVersion());
            if (versionConfig == null) {
                continue;
            }

            RegistrySelectionResult selected = RegistrySelectionResult.builder()
                    .registryConfiguration(registryConfig)
                    .versionConfiguration(versionConfig)
                    .build();
            SdmxFormat returnFormat = determineStructureReturnFormat(selected, parsedMediaType);

            String queryAgencyId = getVersionSpecificQueryId(SDMX_30_ALL_WILDCARD, versionConfig);
            String queryResourceId = getVersionSpecificQueryId(resourceId, versionConfig);
            String queryVersion = getVersionSpecificQueryId(version, versionConfig);

            queries.add(TranslatedStructureQuery.builder()
                    .registryConfiguration(registryConfig)
                    .versionConfiguration(versionConfig)
                    .structure(getStructure(versionConfig, structureType, queryAgencyId, queryResourceId, queryVersion))
                    .references(references)
                    .detail(detail)
                    .contentType(parsedMediaType.getMediaType())
                    .registryReturnFormat(returnFormat)
                    .build());
        }
        return queries;
    }

    /**
     * Selects a version configuration on the given registry that supports the requested structure type.
     * Prefers the desired version (from the parsed Accept header) when available; otherwise prefers
     * SDMX 3.0, then SDMX 2.1. Returns {@code null} when no version of this registry supports the type --
     * the caller should treat that as "skip this registry in the fan-out", not a failure.
     */
    @Nullable
    private VersionSpecificRegistryConfiguration selectVersionForStructureType(
            RegistryConfiguration registryConfig,
            String structureType,
            @Nullable SdmxVersion desiredVersion
    ) {
        if (desiredVersion != null) {
            VersionSpecificRegistryConfiguration v = registryConfig.getVersionConfiguration(desiredVersion);
            if (v != null && supportsStructureType(v, structureType)) {
                return v;
            }
        }
        VersionSpecificRegistryConfiguration v30 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0);
        if (v30 != null && supportsStructureType(v30, structureType)) {
            return v30;
        }
        VersionSpecificRegistryConfiguration v21 = registryConfig.getVersionConfiguration(SdmxVersion.SDMX_2_1);
        if (v21 != null && supportsStructureType(v21, structureType)) {
            return v21;
        }
        return null;
    }

    private static boolean supportsStructureType(VersionSpecificRegistryConfiguration versionConfig, String structureType) {
        StructureEndpointConfiguration cfg = versionConfig.getStructureEndpointConfig();
        return cfg != null && cfg.getSupportedStructures() != null && cfg.getSupportedStructures().contains(structureType);
    }

    private void validateFilters(MultiValueMap<String, String> filters, VersionSpecificRegistryConfiguration versionConfig, SdmxBeans sdmxBeans, String agencyID, String resourceID, String version) {
        var validationResult = filterValidationService.validateFilters(versionConfig, sdmxBeans, filters, agencyID, resourceID, version);
        if (!validationResult.isValid()) {
            throw new FilterValidationException(validationResult.getErrorMessage());
        }
    }

    private Structure getStructure(VersionSpecificRegistryConfiguration versionConfig, String structureType, String agencyId, String resourceId, String version) {
        return new Structure(
                checkStructureTypeIsSupported(structureType, versionConfig),
                agencyId,
                resourceId,
                version
        );
    }

    @Override
    public TranslatedAvailabilityQuery translateAvailabilityQuery(
            String context,
            String agencyID,
            String resourceID,
            String version,
            String key,
            String componentId,
            MultiValueMap<String, String> c,
            Instant updatedAfter,
            String mode,
            String references,
            String startPeriod,
            String endPeriod,
            String reportingYearStartDay,
            String acceptHeader,
            SdmxBeans sdmxBeans,
            String sourceArtefactUrn
    ) {
        // Parse media type from Accept header to get both output type and SDMX version
        MediaTypeParseResult mediaTypeResult = parseMediaType(acceptHeader);
        RegistrySelectionResult selectedRegistry = selectRegistryAndVersion(agencyID, mediaTypeResult.getSdmxVersion(), sourceArtefactUrn);

        MultiValueMap<String, String> filters = extractFilters(c);

        String processedKey = processFilters(
                filters,
                selectedRegistry.getVersionConfiguration(),
                sdmxBeans,
                key,
                agencyID,
                resourceID,
                version
        );

        AvailabilityEndpointConfiguration availabilityEndpointConfig =
                selectedRegistry.getVersionConfiguration().getAvailabilityEndpointConfig();
        if (availabilityEndpointConfig != null && availabilityEndpointConfig.isMergeAllWildcardKey()) {
            processedKey = mergeAllWildcardKey(processedKey);
        }

        boolean is21 = selectedRegistry.getVersionConfiguration().getSdmxVersion() == SdmxVersion.SDMX_2_1;
        if (is21) {
            processedKey = sdmx21QueryNormalizer.toKey(processedKey);
        }

        SdmxFormat returnFormat = determineAvailabilityReturnFormat(
                selectedRegistry,
                mediaTypeResult
        );

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(selectedRegistry.getRegistryConfiguration())
                .versionConfiguration(selectedRegistry.getVersionConfiguration())
                .context(context)
                .agencyID(agencyID)
                .resourceID(resourceID)
                .version(version)
                .key(processedKey)
                .componentId(is21 ? sdmx21QueryNormalizer.toComponentId(componentId) : componentId)
                .filters(filters)
                .updatedAfter(updatedAfter)
                .mode(mode != null ? mode : "exact")
                .references(is21 ? sdmx21QueryNormalizer.toReferences(references) : (references != null ? references : "none"))
                .startPeriod(startPeriod)
                .endPeriod(endPeriod)
                .reportingYearStartDay(reportingYearStartDay)
                .contentType(mediaTypeResult.getMediaType())
                .returnFormat(returnFormat)
                .build();
    }

    private String checkStructureTypeIsSupported(String structureType, VersionSpecificRegistryConfiguration versionConfig) {
        Set<String> supportedStructures = versionConfig.getStructureEndpointConfig().getSupportedStructures();
        if (supportedStructures == null || !supportedStructures.contains(structureType)) {
            throw new UnsupportedContextException(
                    String.format("%s structure type is not supported by SDMX version %s; Supported structures: %s",
                            structureType,
                            versionConfig.getSdmxVersion(),
                            supportedStructures
                    )
            );
        }
        return structureType;
    }

    /**
     * Extracts c parameter filters from request parameters.
     * Filters are in format: c[componentId]=value
     */
    private MultiValueMap<String, String> extractFilters(MultiValueMap<String, String> c) {
        if (c == null || c.isEmpty()) {
            return c;
        }

        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();

        for (Map.Entry<String, List<String>> entry : c.entrySet()) {
            String paramName = entry.getKey();
            if (paramName.startsWith("c[") && paramName.endsWith("]")) {
                List<String> values = entry.getValue();
                if (values != null && values.size() > 1) {
                    throw new FilterValidationException(String.format(
                            "Query parameter '%s' appears %d times but may only be used once per Component. " +
                                    "Combine values in a single parameter: use '+' for AND (e.g. '%s=ge:A+le:B') " +
                                    "or ',' for OR (e.g. '%s=A,B').",
                            paramName, values.size(), paramName, paramName
                    ));
                }
                String componentId = paramName.substring(2, paramName.length() - 1);
                filters.put(componentId, values);
            }
        }

        return filters.isEmpty() ? null : filters;
    }

    /**
     * Processes filters: validates them and merges into key for SDMX 2.1 registries.
     * For SDMX 3.0 registries, filters are validated but not merged (stored as-is).
     *
     * @param filters       Extracted filters (can be null or empty)
     * @param versionConfig The version-specific configuration
     * @param sdmxBeans     The SDMX beans containing the dataflow and data structure
     * @param key           The original key parameter
     * @param agencyID      The agency ID
     * @param resourceID    The resource ID
     * @param version       The version
     * @return Processed key (may include merged filters for SDMX 2.1)
     */
    private String processFilters(
            MultiValueMap<String, String> filters,
            VersionSpecificRegistryConfiguration versionConfig,
            SdmxBeans sdmxBeans,
            String key,
            String agencyID,
            String resourceID,
            String version) {

        if (filters == null || filters.isEmpty()) {
            return key;
        }

        validateFilters(filters, versionConfig, sdmxBeans, agencyID, resourceID, version);

        if (versionConfig.getSdmxVersion() == SdmxVersion.SDMX_3_0) {
            return key;
        }

        return mergeFilters(filters, sdmxBeans, key, agencyID, resourceID, version);
    }

    @Override
    public TranslatedDataQuery translateDataQuery(
            String context,
            String agencyID,
            String resourceID,
            String version,
            String key,
            MultiValueMap<String, String> c,
            Instant updatedAfter,
            Integer firstNObservations,
            Integer lastNObservations,
            String dimensionAtObservation,
            String attributes,
            String measures,
            String includeHistory,
            Integer limit,
            Instant asOf,
            boolean skipEmptySeries,
            String acceptHeader,
            SdmxBeans sdmxBeans,
            String sourceArtefactUrn
    ) {
        // Parse media type from Accept header to get both output type and SDMX version
        MediaTypeParseResult mediaTypeResult = parseMediaType(acceptHeader);
        RegistrySelectionResult selectedRegistry = selectRegistryAndVersion(agencyID, mediaTypeResult.getSdmxVersion(), sourceArtefactUrn);

        MultiValueMap<String, String> filters = extractFilters(c);
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();

        String processedKey;
        String startPeriod = null;
        String endPeriod = null;

        if (filters != null && !filters.isEmpty()) {
            validateFilters(filters, versionConfig, sdmxBeans, agencyID, resourceID, version);
            if (versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1) {
                String timeDimensionId = dimensionService.getTimeDimensionId(sdmxBeans, agencyID, resourceID, version);
                MultiValueMap<String, String> nonTimeFilters = copyFiltersWithout(filters, timeDimensionId);
                List<String> timeValues = filters.get(timeDimensionId);
                if (timeValues != null && !timeValues.isEmpty()) {
                    String combinedTimeValue = String.join(AND_SEPARATOR, timeValues);
                    var range = deriveStartEndFromTimeFilterValue(combinedTimeValue);
                    startPeriod = range[0];
                    endPeriod = range[1];
                }
                processedKey = mergeFilters(nonTimeFilters, sdmxBeans, key, agencyID, resourceID, version);
            } else {
                processedKey = key;
            }
        } else {
            processedKey = key;
        }

        DataEndpointConfiguration dataEndpointConfig = versionConfig.getDataEndpointConfig();
        if (dataEndpointConfig != null && dataEndpointConfig.isReplaceEmptyDimensionsWithWildcard()) {
            processedKey = replaceEmptyDimensionsWithWildcard(processedKey);
        }
        if (dataEndpointConfig != null && dataEndpointConfig.isMergeAllWildcardKey()) {
            processedKey = mergeAllWildcardKey(processedKey);
        }
        // Last, so the 2.1 form wins over the 3.0-registry key knobs above.
        if (versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1) {
            processedKey = sdmx21QueryNormalizer.toKey(processedKey);
        }

        SdmxFormat returnFormat = determineDataReturnFormat(
                selectedRegistry,
                mediaTypeResult
        );

        return TranslatedDataQuery.builder()
                .registryConfiguration(selectedRegistry.getRegistryConfiguration())
                .versionConfiguration(versionConfig)
                .context(context)
                .agencyID(agencyID)
                .resourceID(resourceID)
                .version(version)
                .key(processedKey)
                .startPeriod(startPeriod)
                .endPeriod(endPeriod)
                .filters(filters)
                .updatedAfter(updatedAfter)
                .firstNObservations(firstNObservations)
                .lastNObservations(lastNObservations)
                .dimensionAtObservation(dimensionAtObservation)
                .attributes(attributes)
                .measures(measures)
                .skipEmptySeries(skipEmptySeries)
                .limit(limit)
                .asOf(asOf)
                .contentType(mediaTypeResult.getMediaType())
                .returnFormat(returnFormat)
                .build();

    }

    private String mergeFilters(MultiValueMap<String, String> filters, SdmxBeans sdmxBeans, String key, String agencyID, String resourceID, String version) {
        if (filters == null || filters.isEmpty()) {
            return key;
        }
        // DSD-declared order, never sorted: an SDMX 2.1 key is read by position, so sorting the
        // dimension IDs would hand each value to whichever dimension happens to share its index.
        List<String> dimensionOrder = dimensionService.getDimensionIds(
                sdmxBeans,
                agencyID,
                resourceID,
                version
        );
        return filterTranslator.mergeFiltersIntoKey(key, filters, dimensionOrder);
    }

    /**
     * Determines the return format for structure queries based on Accept header details.
     * Uses MediaTypeParseResult to get detailed format information (not just OutputFormat).
     * If bypass is enabled and requested format matches supportedFormats, uses matching format.
     * Otherwise, uses defaultFormat. Throws exception if defaultFormat is not configured.
     */
    //TODO WRITE TESTS FOR IT
    private SdmxFormat determineStructureReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        StructureEndpointConfiguration structureConfig = versionConfig.getStructureEndpointConfig();
        if (structureConfig == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Structure endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassStructureFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            SdmxFormat matchingFormat = findMatchingFormat(
                    structureConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        SdmxFormat defaultFormat = structureConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Registry %s (version %s) does not support structure format %s and no default format is configured",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion(),
                            requestedMediaType)
            );
        }
        return defaultFormat;
    }

    /**
     * Determines the return format for data queries based on Accept header details.
     * Uses MediaTypeParseResult to get detailed format information (not just OutputFormat).
     * If bypass is enabled and requested format matches supportedFormats, uses matching format.
     * Otherwise, uses defaultFormat. Throws exception if defaultFormat is not configured.
     */
    private SdmxFormat determineDataReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
        if (dataConfig == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Data endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // CSV hard override: if client requests CSV and registry supports it, use native CSV
        if (requestedMediaType.getSubtype().contains("csv")) {
            SdmxFormat csvFormat = findMatchingFormat(dataConfig.getSupportedFormats(), requestedMediaType);
            if (csvFormat != null) {
                return csvFormat;
            }
        }

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassDataFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            SdmxFormat matchingFormat = findMatchingFormat(
                    dataConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        SdmxFormat defaultFormat = dataConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Registry %s (version %s) does not support data format %s and no default format is configured",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion(),
                            requestedMediaType)
            );
        }
        return defaultFormat;
    }

    /**
     * Determines the return format for availability queries based on Accept header details.
     * Uses MediaTypeParseResult to get detailed format information (not just OutputFormat).
     * If bypass is enabled and requested format matches supportedFormats, uses matching format.
     * Otherwise, uses defaultFormat. Throws exception if defaultFormat is not configured.
     */
    //TODO WRITE TESTS FOR IT
    private SdmxFormat determineAvailabilityReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        AvailabilityEndpointConfiguration availabilityConfig = versionConfig.getAvailabilityEndpointConfig();
        if (availabilityConfig == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Availability endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassAvailabilityFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            SdmxFormat matchingFormat = findMatchingFormat(
                    availabilityConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        SdmxFormat defaultFormat = availabilityConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalRegistryConfigurationException(
                    String.format("Registry %s (version %s) does not support availability format %s and no default format is configured",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion(),
                            requestedMediaType)
            );
        }
        return defaultFormat;
    }

    /**
     * Finds a matching format from supportedFormats list that matches the requested media type.
     *
     * @param supportedFormats   list of formats that registry can return
     * @param requestedMediaType detailed media type from Accept header
     * @return matching SdmxFormat, or null if not found
     */
    //TODO WRITE TESTS FOR IT
    private SdmxFormat findMatchingFormat(
            List<SdmxFormat> supportedFormats,
            MediaType requestedMediaType
    ) {
        if (supportedFormats == null || supportedFormats.isEmpty()) {
            return null;
        }

        // Find first matching format - if multiple matches exist, return any of them
        return supportedFormats.stream()
                .filter(format -> FormatSupportChecker.formatMatches(format, requestedMediaType))
                .findFirst()
                .orElse(null);
    }

    /**
     * Replaces empty dimension positions in a key with '*'.
     * Empty positions arise when the key contains consecutive dots (e.g., ".A..B" -> "*.A.*.B").
     */
    static String replaceEmptyDimensionsWithWildcard(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        String[] parts = key.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                parts[i] = "*";
            }
        }
        return String.join(".", parts);
    }

    /**
     * Collapses a key whose every dimension position is '*' to a single '*'.
     * Leaves all other keys (including single '*', null, empty, and keys with any literal value) unchanged.
     */
    static String mergeAllWildcardKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        String[] parts = key.split("\\.", -1);
        for (String part : parts) {
            if (!"*".equals(part)) {
                return key;
            }
        }
        return "*";
    }

}
