package com.epam.sdmxproxy.services.availability;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.services.availability.harvest.DimensionIds;
import com.epam.sdmxproxy.services.availability.harvest.HarvestedCoverage;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.registry.ContentConstraintBean;
import io.sdmx.api.sdmx.model.beans.registry.CubeRegionBean;
import io.sdmx.api.sdmx.model.beans.registry.KeyValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads the registry's {@code Actual} content constraint as coverage -- see design 040.
 * <p>
 * Serves the availability request that carries <strong>no narrowing at all</strong>, and nothing
 * else. That request is the one the constraint answers exactly: measured against a whole-cube
 * probe on Eurostat, the two agree on every dimension with zero divergence in either direction,
 * while costing 5.6--40 KB and ~0.3 s instead of 1.8--139 MB and 1--71 s.
 * <p>
 * It must never serve a narrowed request. The resource is dataflow-scoped and silently ignores
 * narrowing -- {@code ?c[geo]=EL} returns a byte-identical response, a key in the path returns
 * 405 -- so answering a filtered request from it would report the whole cube as if it had been
 * narrowed. Nothing would fail; every narrowing decision downstream would simply be wrong.
 * {@link DataQueryAvailabilityEmulator} owns that routing decision.
 * <p>
 * Returns {@link HarvestedCoverage} rather than a rendered document so that both emulation paths
 * feed the same {@link AvailabilityConstraintSynthesizer}. That is what keeps the unfiltered and
 * narrowed responses structurally identical, which in turn is what makes the consumer's
 * "filtered is a subset of unfiltered" invariant checkable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnfilteredConstraintSource {

    private static final String SDMX_21_CONSTRAINT_TYPE = "contentconstraint";
    private static final String SDMX_30_CONSTRAINT_TYPE = "dataconstraint";

    /**
     * Fetches the constraint and reduces it to per-dimension coverage.
     * <p>
     * The fetch goes through the caller's structure cache, so the unfiltered response and every
     * intersection universe for the same dataflow share one upstream call.
     *
     * @param beans dataflow structures, for the DSD's dimension list
     */
    public HarvestedCoverage coverage(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityEmulationContext context
    ) {
        AvailabilityEmulationConfiguration emulation =
                query.getVersionConfiguration().getAvailabilityEndpointConfig().getEmulation();
        String structureType = resolveConstraintStructureType(query, emulation);

        TranslatedStructureQuery constraintQuery = context.structureQuery(
                structureType, query.getAgencyID(), query.getResourceID(), query.getVersion());
        SdmxBeans constraintBeans = context.fetchStructureBeans(constraintQuery);

        ContentConstraintBean constraint = firstConstraint(constraintBeans, structureType, query);
        CubeRegionBean region = constraint.getIncludedCubeRegion();
        if (region == null) {
            throw new AvailabilityEmulationException(
                    "Constraint " + constraint.getId() + " for " + describe(query)
                            + " carries no included cube region, so it reports no coverage");
        }

        boolean includeTimePeriod = emulation.isIncludeTimePeriod();
        String timeDimensionId = DimensionIds.timeDimensionId(beans);
        List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(beans);

        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (String dimensionId : dimensionIds) {
            values.put(dimensionId, new LinkedHashSet<>());
        }

        for (KeyValues keyValues : region.getKeyValues()) {
            String componentId = keyValues.getId();
            if (componentId == null) {
                continue;
            }
            if (componentId.equals(timeDimensionId)) {
                // The registry does report time coverage here, but a probe never can. Dropping it
                // keeps both emulation paths structurally identical -- see design 040 for why the
                // consumer neither needs nor can use it on this path.
                if (!includeTimePeriod) {
                    log.debug("Dropping {} key value from the emulated response for {}", componentId, describe(query));
                    continue;
                }
                values.computeIfAbsent(componentId, unused -> new LinkedHashSet<>())
                        .addAll(nullSafe(keyValues.getValues()));
                continue;
            }
            Set<String> target = values.get(componentId);
            if (target == null) {
                // A component the DSD does not declare as a dimension (an attribute region, or a
                // stale constraint). Reporting it would put a key value in the response for a
                // component the consumer has no dimension for.
                log.debug("Ignoring constraint component '{}' which is not a DSD dimension of {}",
                        componentId, describe(query));
                continue;
            }
            target.addAll(nullSafe(keyValues.getValues()));
        }

        List<String> emptyDimensions = values.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (!emptyDimensions.isEmpty()) {
            // The consumer requires a key value for every coded dimension on the unfiltered
            // request, and treats an empty one as "no data at all for this dimension" -- which
            // would take the dataset out of every index. A constraint that cannot describe the
            // whole cube is a configuration problem, not a degraded answer.
            throw new AvailabilityEmulationException(
                    "Constraint " + constraint.getId() + " for " + describe(query)
                            + " reports no values for dimension(s) " + emptyDimensions
                            + "; it cannot serve as the unfiltered availability answer");
        }

        log.info("Unfiltered availability for {} served from {} ({} dimensions)",
                describe(query), structureType, values.size());
        // Series count is unknown from a constraint; the consumer does not read it, and the
        // limit-emulation bisect is forbidden from combining with emulation anyway.
        return new HarvestedCoverage(values, -1L);
    }

    /**
     * Resource name for the constraint: configured value, else the SDMX version's own -- SDMX 2.1
     * calls it {@code contentconstraint}, SDMX 3.0 {@code dataconstraint}, and the proxy performs
     * no structure-type renaming between the two.
     */
    public String resolveConstraintStructureType(
            TranslatedAvailabilityQuery query,
            AvailabilityEmulationConfiguration emulation
    ) {
        if (emulation.getConstraintStructureType() != null && !emulation.getConstraintStructureType().isBlank()) {
            return emulation.getConstraintStructureType();
        }
        SdmxVersion sdmxVersion = query.getVersionConfiguration().getSdmxVersion();
        return sdmxVersion == SdmxVersion.SDMX_3_0 ? SDMX_30_CONSTRAINT_TYPE : SDMX_21_CONSTRAINT_TYPE;
    }

    private ContentConstraintBean firstConstraint(
            SdmxBeans constraintBeans,
            String structureType,
            TranslatedAvailabilityQuery query
    ) {
        Set<ContentConstraintBean> constraints = constraintBeans == null
                ? Set.of()
                : constraintBeans.getContentConstraintBeans();
        if (constraints == null || constraints.isEmpty()) {
            throw new AvailabilityEmulationException(
                    "Registry returned no " + structureType + " for " + describe(query)
                            + ", so unfiltered availability cannot be emulated from it");
        }
        if (constraints.size() > 1) {
            // The consumer raises on a second constraint. Reducing to the first is the same
            // defensive narrowing the response would need anyway.
            log.warn("Registry returned {} constraints for {}; using the first", constraints.size(), describe(query));
        }
        return constraints.iterator().next();
    }

    private static Set<String> nullSafe(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    private static String describe(TranslatedAvailabilityQuery query) {
        return query.getAgencyID() + ":" + query.getResourceID() + "(" + query.getVersion() + ")";
    }
}
