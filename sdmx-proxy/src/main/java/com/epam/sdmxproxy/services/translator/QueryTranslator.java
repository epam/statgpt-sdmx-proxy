package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;

public interface QueryTranslator {

    /**
     * Translates structure query parameters into a {@link TranslatedStructureQuery} bound to a specific registry.
     * <p>
     * Registry selection uses {@code sourceArtefactUrn} for cross-reference routing when present,
     * falling back to standard agency-based routing otherwise. See {@code AgencyRoutingService} for the
     * two-path algorithm.
     * <p>
     * Throws {@link com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException} if agencyID is "*"
     * or contains commas. Clients should use the /structure/agencyscheme endpoint to discover agencies.
     *
     * @param structureType     structure type (e.g. "datastructure", "codelist", "dataflow")
     * @param agencyID          agency ID from the request path
     * @param resourceID        resource ID (can be null for "all")
     * @param version           version (can be null for "latest")
     * @param references        references parameter
     * @param detail            detail parameter
     * @param acceptHeader      Accept header from the client request
     * @param sourceArtefactUrn URN from X-Source-Artefact-Urn header for cross-reference routing (can be null)
     * @return translated query with resolved registry and version configuration
     */
    TranslatedStructureQuery translateStructureQuery(
            String structureType,
            String agencyID,
            String resourceID,
            String version,
            String references,
            String detail,
            String acceptHeader,
            String sourceArtefactUrn
    );

    /**
     * Translates an agency ID into a minimal {@link TranslatedStructureQuery} for agency scheme discovery.
     * <p>
     * Used by {@link com.epam.sdmxproxy.services.agencyscheme.AgencySchemeService} to fetch dataflows
     * from a registry and extract sub-agency IDs. Unlike {@link #translateStructureQuery}, this method
     * does not accept cross-reference routing or client format parameters — it uses defaults suitable
     * for internal consumption.
     *
     * @param agencyID agency ID used to resolve the target registry
     * @return translated query with resolved registry and version configuration
     */
    TranslatedStructureQuery translateStructureQueryForAgencySchemaDiscovery(
            String agencyID
    );

    /**
     * Translates a wildcard structure query ({@code agencyId="*"}) into one
     * {@link TranslatedStructureQuery} per configured registry that supports the
     * requested {@code structureType}. Caller is responsible for checking the
     * {@code structureFanOutEnabled} toggle before invoking.
     * <p>
     * Returns an empty list if no configured registry supports the structure type.
     * Callers should treat that as an empty merged response, not a failure.
     *
     * @param structureType structure type (e.g. "datastructure", "codelist")
     * @param resourceID    resource ID (may be {@code null} or {@code "*"})
     * @param version       version (may be {@code null} or {@code "*"})
     * @param references    references parameter, pass-through
     * @param detail        detail parameter, pass-through
     * @param acceptHeader  client Accept header (drives content type and version selection)
     * @return one translated query per participating registry; possibly empty
     */
    List<TranslatedStructureQuery> translateWildcardStructureFanOut(
            String structureType,
            String resourceID,
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
            SdmxBeans sdmxBeans,
            String sourceArtefactUrn
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
            SdmxBeans sdmxBeans,
            String sourceArtefactUrn
    );
}
