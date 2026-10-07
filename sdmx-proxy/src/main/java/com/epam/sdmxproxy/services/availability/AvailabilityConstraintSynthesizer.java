package com.epam.sdmxproxy.services.availability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.jsdmx.infomodel.sdmx30.ArtefactsImpl;
import com.epam.jsdmx.infomodel.sdmx30.ConstraintRoleType;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegion;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionImpl;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionKey;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionKeyImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataConstraint;
import com.epam.jsdmx.infomodel.sdmx30.DataConstraintImpl;
import com.epam.jsdmx.infomodel.sdmx30.InternationalString;
import com.epam.jsdmx.infomodel.sdmx30.MaintainableArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.MemberValueImpl;
import com.epam.jsdmx.infomodel.sdmx30.SelectionValue;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.services.availability.harvest.DimensionIds;
import com.epam.sdmxproxy.services.availability.harvest.HarvestedCoverage;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the availability artefact from harvested coverage -- see design 040.
 * <p>
 * Constructed directly in the SDMX 3.0 information model rather than as an {@code io.sdmx} bean,
 * for two reasons. There is no upstream constraint document to convert on the probe path. And
 * {@code ContentConstraintMapper} renders a time range through {@code SdmxDate -> Date ->
 * Instant.toString()}, which turns {@code 2020-Q1} into {@code 2020-01-01T00:00:00Z};
 * {@code TimeRangePeriodImpl.setPeriod(String)} takes the reporting period verbatim, so this
 * layer stays lossless if {@code includeTimePeriod} is ever switched on.
 * <p>
 * Three properties the consumer depends on, all enforced here:
 * <ul>
 *   <li>exactly one data constraint holding exactly one cube region -- a second of either raises
 *       in the consumer;</li>
 *   <li>a key value for <em>every</em> DSD non-time dimension, empty where the probe observed
 *       nothing. A missing key value takes the dataset offline; an empty one is the correct way
 *       to say "no data under this filter";</li>
 *   <li>{@code id}, {@code version}, {@code agencyID} and a name present. Their values are never
 *       inspected, but their absence is a schema violation.</li>
 * </ul>
 */
@Slf4j
@Component
public class AvailabilityConstraintSynthesizer {

    /**
     * Version stamped on the synthesized constraint when the request's own version slot is a
     * wildcard or otherwise unparseable. The consumer requires the field to be present and never
     * reads it.
     */
    private static final String FALLBACK_VERSION = "1.0";

    /**
     * Renders the coverage as a one-region {@code Actual} data constraint attached to the
     * dataflow the request named.
     */
    public Artefacts synthesize(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            HarvestedCoverage coverage
    ) {
        List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(beans);

        List<CubeRegionKey> keys = new ArrayList<>(dimensionIds.size());
        for (String dimensionId : dimensionIds) {
            Set<String> values = coverage.valuesByDimensionId()
                    .getOrDefault(dimensionId, new LinkedHashSet<>());
            keys.add(cubeRegionKey(dimensionId, values));
        }

        CubeRegionImpl region = new CubeRegionImpl();
        region.setIncluded(true);
        region.setCubeRegionKeys(keys);

        DataConstraintImpl constraint = new DataConstraintImpl();
        constraint.setId(constraintId(query));
        constraint.setOrganizationId(query.getAgencyID());
        constraint.setVersion(version(query));
        constraint.setName(new InternationalString(Map.of("en", constraintName(query))));
        constraint.setConstraintRoleType(ConstraintRoleType.ACTUAL_CONTENT);
        constraint.setConstrainedArtefacts(List.of(dataflowReference(query)));
        constraint.setCubeRegions(List.of(region));

        ArtefactsImpl artefacts = new ArtefactsImpl();
        artefacts.setDataConstraints(new LinkedHashSet<>(Set.<DataConstraint>of(constraint)));

        log.info(
                "Synthesized emulated availability for {}:{}({}) from {} series across {} dimensions",
                query.getAgencyID(), query.getResourceID(), query.getVersion(),
                coverage.seriesCount(), dimensionIds.size()
        );
        return artefacts;
    }

    private CubeRegionKey cubeRegionKey(String dimensionId, Set<String> values) {
        List<SelectionValue> selectionValues = new ArrayList<>(values.size());
        for (String value : values) {
            MemberValueImpl memberValue = new MemberValueImpl();
            memberValue.setValue(value);
            selectionValues.add(memberValue);
        }
        CubeRegionKeyImpl key = new CubeRegionKeyImpl();
        key.setComponentId(dimensionId);
        key.setIncluded(true);
        // The consumer's schema declares removePrefix as required, with no default.
        key.setRemovePrefix(false);
        key.setSelectionValues(selectionValues);
        return key;
    }

    /**
     * A stable, unique-per-dataflow id. Unique matters -- the consumer keys constraints by id --
     * while the value itself is never read.
     */
    private String constraintId(TranslatedAvailabilityQuery query) {
        return query.getResourceID();
    }

    private String constraintName(TranslatedAvailabilityQuery query) {
        return "Emulated availability for " + query.getAgencyID() + ":" + query.getResourceID();
    }

    private Version version(TranslatedAvailabilityQuery query) {
        String requested = query.getVersion();
        if (requested != null && !requested.isBlank()) {
            try {
                return Version.createFromString(requested);
            } catch (RuntimeException e) {
                // Wildcard ('*', '+', 'latest') and other non-semver slots are legal in a request
                // but not on an artefact. The consumer requires the field, never its value.
                log.debug("Request version '{}' is not a artefact version, stamping {}", requested, FALLBACK_VERSION);
            }
        }
        return Version.createFromString(FALLBACK_VERSION);
    }

    private MaintainableArtefactReference dataflowReference(TranslatedAvailabilityQuery query) {
        return new MaintainableArtefactReference(
                query.getResourceID(),
                query.getAgencyID(),
                version(query),
                StructureClassImpl.DATAFLOW
        );
    }
}
