package com.epam.sdmxproxy.services.misc;

import com.epam.jsdmx.infomodel.sdmx30.VersionReference;
import com.epam.jsdmx.infomodel.sdmx30.WildcardReferenceMatcher;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Implementation of DimensionService.
 * Service to retrieve dimension IDs from a dataflow or data structure definition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DimensionServiceImpl implements DimensionService {
    private static final String DEFAULT_TIME_DIMENSION_ID = "TIME_PERIOD";

    @Override
    public Set<String> getDimensionIds(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version) {
        // Find the dataflow
        DataflowBean dataflow = sdmxBeans.getDataflows().stream()
                .filter(df -> df.getId().equals(resourceId) && df.getAgencyId().equals(agencyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dataflow not found: " + resourceId));

        // Get the DSD referenced by the dataflow
        String dsdAgency = dataflow.getDataStructureRef().getReference().getAgencyId();
        String dsdId = dataflow.getDataStructureRef().getReference().getMaintainableId();
        String dsdVersion = dataflow.getDataStructureRef().getReference().getVersion().toString();

        // Get dimensions from DSD
        return getDimensionIdsFromDsd(sdmxBeans, dsdAgency, dsdId, dsdVersion);
    }

    @Override
    public Set<String> getDimensionIdsFromDsd(SdmxBeans sdmxBeans, String agency, String id, String version) {
        DataStructureBean dsd = resolveDsd(sdmxBeans, agency, id, version);

        return dsd.getDimensionList().getDimensions().stream()
                .filter(dimension -> !dimension.isTimeDimension())
                .map(IdentifiableBean::getId)
                .collect(Collectors.toSet());
    }


    @Override
    public String getTimeDimensionId(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version) {
        DataStructureBean dsd = getDsdFromDataflow(sdmxBeans, agencyId, resourceId);

        return dsd.getDimensionList().getDimensions().stream()
                .filter(DimensionBean::isTimeDimension)
                .map(IdentifiableBean::getId)
                .findFirst()
                .orElse(DEFAULT_TIME_DIMENSION_ID);
    }

    private DataStructureBean getDsdFromDataflow(SdmxBeans sdmxBeans, String agencyId, String resourceId) {
        DataflowBean dataflow = sdmxBeans.getDataflows().stream()
                .filter(df -> df.getId().equals(resourceId) && df.getAgencyId().equals(agencyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dataflow not found: " + resourceId));

        String dsdAgency = dataflow.getDataStructureRef().getReference().getAgencyId();
        String dsdId = dataflow.getDataStructureRef().getReference().getMaintainableId();
        String dsdVersion = dataflow.getDataStructureRef().getReference().getVersion().toString();

        return resolveDsd(sdmxBeans, dsdAgency, dsdId, dsdVersion);
    }

    /**
     * Resolve a DSD by (agency, id, version) where the version reference may be SDMX 3.0 wildcarded
     * (e.g. {@code 9.0+.0}). For a specific version, an exact match is required. For a wildcard,
     * the latest specific version that matches the wildcard scope is returned (per SDMX 3.0 version
     * management rules; jsdmx's {@link WildcardReferenceMatcher} and
     * {@link VersionReference#getComparator()} implement the semantics).
     *
     * <p>Falls back to literal string equality when either the requested or candidate version is
     * not parseable as an SDMX 3.0 {@link VersionReference} (e.g. legacy two-part versions).
     */
    private static DataStructureBean resolveDsd(SdmxBeans beans, String agency, String id, String versionRef) {
        Predicate<DataStructureBean> agencyAndId = dsd -> agency.equals(dsd.getAgencyId()) && id.equals(dsd.getId());

        VersionReference requested;
        try {
            requested = VersionReference.createFromString(versionRef);
        } catch (RuntimeException notSdmx3) {
            log.debug("Version '{}' is not SDMX 3.0 parseable; falling back to literal match for DSD {}:{}", versionRef, agency, id);
            return beans.getDataStructures().stream()
                    .filter(agencyAndId)
                    .filter(dsd -> versionRef.equals(dsd.getVersion().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("DataStructure not found: " + id));
        }

        if (requested.isSpecific()) {
            return beans.getDataStructures().stream()
                    .filter(agencyAndId)
                    .filter(dsd -> requested.equals(parseSilently(dsd.getVersion().toString())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("DataStructure not found: " + id + " (version " + versionRef + ")"));
        }

        WildcardReferenceMatcher matcher = new WildcardReferenceMatcher(requested);
        Comparator<VersionReference> byVersion = VersionReference.getComparator();
        return beans.getDataStructures().stream()
                .filter(agencyAndId)
                .filter(dsd -> {
                    VersionReference candidate = parseSilently(dsd.getVersion().toString());
                    return candidate != null && candidate.isSpecific() && matcher.matches(candidate);
                })
                .max(Comparator.comparing(dsd -> parseSilently(dsd.getVersion().toString()), Comparator.nullsFirst(byVersion)))
                .orElseThrow(() -> new IllegalArgumentException("DataStructure not found: " + id + " (no specific version matches wildcard " + versionRef + ")"));
    }

    private static VersionReference parseSilently(String version) {
        try {
            return VersionReference.createFromString(version);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
