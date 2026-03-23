package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;

public interface QueryTranslator {

    TranslatedStructureQuery translateStructureQuery(
            String structureType,
            String agencyID,
            String resourceID,
            String version,
            String references,
            String detail,
            String acceptHeader
    );

    /**
     * Checks if fan-out is needed for the given agency ID.
     *
     * @param agencyId Agency ID to check
     * @return true if fan-out is needed, false otherwise
     */
    boolean requiresFanOut(String agencyId);

    /**
     * Translates structure query parameters to queries for fan-out.
     * Handles two cases:
     * 1. Wildcard agency ("*") → creates queries for all registries
     * 2. Comma-separated agencies → creates queries per registry (only if agencies are in different registries)
     * <p>
     * If comma-separated agencies all map to same registry, returns single TranslatedStructureQuery (normal path).
     *
     * @param structureType Structure type (e.g., "datastructure")
     * @param agencyId      Agency ID - can be "*" (wildcard) or comma-separated list (e.g., "BIS,AMF")
     *                      Note: "all" is treated as a regular agency ID (not a wildcard)
     * @param resourceId    Resource ID (can be null for "all")
     * @param version       Version (can be null for "latest")
     * @param references    References parameter
     * @param detail        Detail parameter
     * @param acceptHeader  Accept header
     * @return List of TranslatedStructureQuery (one per registry for fan-out, or single query for normal path)
     * @throws IllegalArgumentException if any agency is invalid (not found in any registry)
     */
    List<TranslatedStructureQuery> translateToFanOutStructures(
            String structureType,
            String agencyId,
            String resourceId,
            String version,
            String references,
            String detail,
            String acceptHeader
    );


    TranslatedDataQuery translateDataQuery(
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
            SdmxBeans sdmxBeans
    );

    TranslatedAvailabilityQuery translateAvailabilityQuery(
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
            SdmxBeans sdmxBeans
    );
}

