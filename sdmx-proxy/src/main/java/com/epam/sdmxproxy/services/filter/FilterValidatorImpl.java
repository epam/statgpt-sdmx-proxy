package com.epam.sdmxproxy.services.filter;

import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.services.misc.DimensionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of SdmxFilterValidationService.
 * Service to orchestrate filter validation for SDMX queries.
 * Handles SDMX version checks, dimension fetching, and validation.
 * Works for both availability and data queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterValidatorImpl implements FilterValidator {

    private static final Set<String> SUPPORTED_OPERATORS = Set.of("eq", "");
    private static final Set<String> TIME_DIMENSION_OPERATORS = Set.of("eq", "", "ge", "gt", "le", "lt");
    private static final String OPERATOR_SEPARATOR = ":";
    private static final String MULTIPLE_VALUES_SEPARATOR = ",";
    private static final String AND_SEPARATOR = "+";
    private final DimensionService dimensionService;

    @Override
    public FilterValidationResult validateFilters(
            VersionSpecificRegistryConfiguration versionConfig,
            SdmxBeans sdmxBeans,
            MultiValueMap<String, String> filters,
            String agencyID,
            String resourceID,
            String version) {

        if (versionConfig.getSdmxVersion() != SdmxVersion.SDMX_2_1 || (filters == null || filters.isEmpty())) {
            return FilterValidationResult.valid();
        }

        List<String> dimensionIds = dimensionService.getDimensionIds(sdmxBeans, agencyID, resourceID, version);
        String timeDimensionId = dimensionService.getTimeDimensionId(sdmxBeans, agencyID, resourceID, version);

        return validateFilters(filters, dimensionIds, timeDimensionId);
    }

    private FilterValidationResult validateFilters(
            MultiValueMap<String, String> filters,
            List<String> dimensionIds,
            String timeDimensionId) {
        if (filters == null || filters.isEmpty()) {
            return FilterValidationResult.valid();
        }

        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            String componentId = entry.getKey();
            List<String> values = entry.getValue();

            if (values == null || values.isEmpty()) {
                continue;
            }

            if (componentId.equals(timeDimensionId)) {
                for (String value : values) {
                    FilterValidationResult result = validateTimeDimensionFilterValue(componentId, value);
                    if (!result.isValid()) {
                        return result;
                    }
                }
                continue;
            }

            if (!dimensionIds.contains(componentId)) {
                return FilterValidationResult.invalid(
                        String.format("Filter on component '%s' is not supported. SDMX 2.1 API only supports filters on dimensions. " +
                                "Please remove this filter or use a different API endpoint.", componentId)
                );
            }

            for (String value : values) {
                FilterValidationResult result = validateFilterValue(componentId, value);
                if (!result.isValid()) {
                    return result;
                }
            }
        }

        return FilterValidationResult.valid();
    }

    private FilterValidationResult validateTimeDimensionFilterValue(String componentId, String value) {
        if (value == null || value.trim().isEmpty()) {
            return FilterValidationResult.invalid(
                    String.format("Empty filter value for component '%s' is not supported.", componentId)
            );
        }
        if (value.contains(MULTIPLE_VALUES_SEPARATOR)) {
            return FilterValidationResult.invalid(
                    String.format("Multiple values (OR) for component '%s' are not supported by SDMX 2.1 API. " +
                            "Please use a single value or remove this filter.", componentId)
            );
        }
        for (String part : value.split("\\" + AND_SEPARATOR)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String operator = extractOperator(trimmed);
            if (!TIME_DIMENSION_OPERATORS.contains(operator)) {
                return FilterValidationResult.invalid(
                        String.format("Operator '%s' for time dimension '%s' is not supported. " +
                                "Only eq, ge, gt, le, lt are allowed for time period filters.", operator, componentId)
                );
            }
        }
        return FilterValidationResult.valid();
    }

    private FilterValidationResult validateFilterValue(String componentId, String value) {
        if (value == null || value.trim().isEmpty()) {
            return FilterValidationResult.invalid(
                    String.format("Empty filter value for component '%s' is not supported.", componentId)
            );
        }
        // `,` is the SDMX 3.0 OR separator and SDMX 2.1 expresses the same thing with `+` inside a
        // key position, so a multi-value filter is translated (see FilterTranslatorImpl), not refused.
        if (value.contains(AND_SEPARATOR)) {
            return FilterValidationResult.invalid(
                    String.format("AND operations (using '+') for component '%s' are not supported by SDMX 2.1 API. " +
                            "Please use a single value or remove this filter.", componentId)
            );
        }
        for (String alternative : value.split(MULTIPLE_VALUES_SEPARATOR)) {
            if (alternative.trim().isEmpty()) {
                return FilterValidationResult.invalid(
                        String.format("Empty filter value for component '%s' is not supported.", componentId)
                );
            }
            String operator = extractOperator(alternative);
            if (!SUPPORTED_OPERATORS.contains(operator)) {
                return FilterValidationResult.invalid(
                        String.format("Operator '%s' for component '%s' is not supported by SDMX 2.1 API. " +
                                        "Only equals (eq) operator is supported. Please use 'c[%s]=value' format or remove this filter.",
                                operator, componentId, componentId)
                );
            }
        }
        return FilterValidationResult.valid();
    }

    private String extractOperator(String value) {
        int separatorIndex = value.indexOf(OPERATOR_SEPARATOR);
        if (separatorIndex > 0) {
            return value.substring(0, separatorIndex);
        }
        return "";
    }

}
