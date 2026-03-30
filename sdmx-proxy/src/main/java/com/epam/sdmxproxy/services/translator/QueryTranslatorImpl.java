package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.common.data.MediaTypeParseResult;
import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.FilterValidationException;
import com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException;
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

import static com.epam.sdmxproxy.common.data.SdmxMediaType.parseMediaType;


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
    private final AgencyRoutingService agencyRoutingService;
    private final FilterValidator filterValidationService;
    private final FilterTranslator filterTranslator;
    private final DimensionService dimensionService;

    private static String getVersionSpecificQueryId(String id, VersionSpecificRegistryConfiguration versionConfig) {
        String queryId = id;
        if (queryId.equals(SDMX_30_ALL_WILDCARD)) {
            queryId = versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1 ? SDMX_21_ALL_WILDCARD : SDMX_30_ALL_WILDCARD;
        }
        return queryId;
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

        throw new IllegalArgumentException(String.format("No suitable SDMX version found for registry: %s, agency: %s", registryConfig.getName(), agencyID));
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
        if ("*".equals(agencyId) || (agencyId != null && agencyId.contains(","))) {
            throw new UnsupportedAgencyWildcardException("Wildcard and comma-separated agency queries are not supported. Use /structure/agencyscheme to discover agencies.");
        }

        MediaTypeParseResult parsedMediaType = parseMediaType(acceptHeader);

        RegistrySelectionResult selectedRegistry = selectRegistryAndVersion(agencyId, parsedMediaType.getSdmxVersion(), sourceArtefactUrn);

        ReturnFormat returnFormat = determineStructureReturnFormat(
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

        ReturnFormat returnFormat = determineAvailabilityReturnFormat(
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
                .componentId(componentId)
                .filters(filters)
                .updatedAfter(updatedAfter)
                .mode(mode != null ? mode : "exact")
                .references(references != null ? references : "none")
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
            throw new IllegalArgumentException(
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
                String componentId = paramName.substring(2, paramName.length() - 1);
                filters.put(componentId, entry.getValue());
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

        ReturnFormat returnFormat = determineDataReturnFormat(
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
        Set<String> dimensionIds = dimensionService.getDimensionIds(
                sdmxBeans,
                agencyID,
                resourceID,
                version
        );
        List<String> dimensionOrder = dimensionIds.stream().sorted().toList();
        return filterTranslator.mergeFiltersIntoKey(key, filters, dimensionOrder);
    }

    /**
     * Determines the return format for structure queries based on Accept header details.
     * Uses MediaTypeParseResult to get detailed format information (not just OutputFormat).
     * If bypass is enabled and requested format matches supportedFormats, uses matching format.
     * Otherwise, uses defaultFormat. Throws exception if defaultFormat is not configured.
     */
    //TODO WRITE TESTS FOR IT
    private ReturnFormat determineStructureReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        StructureEndpointConfiguration structureConfig = versionConfig.getStructureEndpointConfig();
        if (structureConfig == null) {
            throw new IllegalArgumentException(
                    String.format("Structure endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassStructureFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            ReturnFormat matchingFormat = findMatchingFormat(
                    structureConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        ReturnFormat defaultFormat = structureConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalArgumentException(
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
    //TODO WRITE TESTS FOR IT
    private ReturnFormat determineDataReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
        if (dataConfig == null) {
            throw new IllegalArgumentException(
                    String.format("Data endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassDataFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            ReturnFormat matchingFormat = findMatchingFormat(
                    dataConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        ReturnFormat defaultFormat = dataConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalArgumentException(
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
    private ReturnFormat determineAvailabilityReturnFormat(
            RegistrySelectionResult selectedRegistry,
            MediaTypeParseResult parsedMediaType
    ) {
        VersionSpecificRegistryConfiguration versionConfig = selectedRegistry.getVersionConfiguration();
        AvailabilityEndpointConfiguration availabilityConfig = versionConfig.getAvailabilityEndpointConfig();
        if (availabilityConfig == null) {
            throw new IllegalArgumentException(
                    String.format("Availability endpoint configuration is missing for registry %s (version %s)",
                            selectedRegistry.getRegistryConfiguration().getName(),
                            versionConfig.getSdmxVersion())
            );
        }

        MediaType requestedMediaType = parsedMediaType.getMediaType();

        // Check if bypass is possible
        if (FormatSupportChecker.canBypassAvailabilityFormat(versionConfig, requestedMediaType)) {
            // Find matching format from supportedFormats that matches MediaType details
            ReturnFormat matchingFormat = findMatchingFormat(
                    availabilityConfig.getSupportedFormats(),
                    requestedMediaType
            );
            if (matchingFormat != null) {
                return matchingFormat;
            }
        }

        // Use default format
        ReturnFormat defaultFormat = availabilityConfig.getDefaultFormat();
        if (defaultFormat == null) {
            throw new IllegalArgumentException(
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
     * @return matching ReturnFormat, or null if not found
     */
    //TODO WRITE TESTS FOR IT
    private ReturnFormat findMatchingFormat(
            List<ReturnFormat> supportedFormats,
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


}
