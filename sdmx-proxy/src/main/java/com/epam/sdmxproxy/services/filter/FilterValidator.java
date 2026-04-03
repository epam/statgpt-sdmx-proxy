package com.epam.sdmxproxy.services.filter;

import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.springframework.util.MultiValueMap;

/**
 * Service to orchestrate filter validation for SDMX queries.
 * Handles SDMX version checks, dimension fetching, and validation.
 * Works for both availability and data queries.
 */
public interface FilterValidator {

    /**
     * Validates filters for SDMX queries.
     * For SDMX 2.1 registries, validates that filters are compatible with SDMX 2.1 API.
     * For SDMX 3.0 registries, validation is not needed.
     *
     * @param versionConfig The version-specific configuration
     * @param filters       The filters to validate (can be null or empty)
     * @param agencyID      The agency ID
     * @param resourceID    The resource ID
     * @param version       The version
     * @return ValidationResult containing validation status and error message if invalid
     */
    FilterValidationResult validateFilters(
            VersionSpecificRegistryConfiguration versionConfig,
            SdmxBeans sdmxBeans,
            MultiValueMap<String, String> filters,
            String agencyID,
            String resourceID,
            String version);
}
