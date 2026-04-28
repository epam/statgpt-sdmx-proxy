package com.epam.sdmxproxy.services.limit;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.misc.DimensionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Heuristic limit emulation (see design 014). Two-stage algorithm:
 * <ol>
 *   <li><b>TC fast-path</b> -- compute the total combinatorial cube size locally from the
 *       client filter / key plus DSD codelist sizes. When {@code TC <= target} no
 *       availability probe is issued: the registry's series count is bounded by {@code TC},
 *       so the data response cannot exceed the client's limit.</li>
 *   <li><b>Probe-budgeted bisect</b> -- one initial availability probe; if the cube still
 *       overshoots, iterate dims in argmax-cardinality order, doing a bisect between the
 *       last known undershoot and overshoot {@code k}. Total probes capped at the
 *       configured {@code limitEmulationProbeBudget}; on cap or integer-gap convergence,
 *       the dim is locked at the last overshoot {@code k_high} so the registry response
 *       does not undershoot {@code N} (the truncator caps to exactly {@code N}). When a
 *       dim's bisect cannot produce any overshoot inside the budget, the dim's pre-bisect
 *       state is restored before moving on.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitEmulationServiceImpl implements LimitEmulationService {

    private final AvailabilityFixtureService availabilityFixtureService;
    private final AvailabilityResponseParser availabilityResponseParser;
    private final BisectCalculator bisectCalculator;
    private final CodelistSizeResolver codelistSizeResolver;
    private final DimensionService dimensionService;
    private final KeyParser keyParser;

    private static AvailabilityEndpointConfiguration requireAvailabilityConfig(TranslatedDataQuery query) {
        AvailabilityEndpointConfiguration availabilityConfig =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        if (availabilityConfig == null) {
            throw new IllegalStateException(
                    "availabilityEndpointConfig is required for supportsLimit=false registries");
        }
        return availabilityConfig;
    }

    // ===================== TC fast-path =====================

    /**
     * TranslatedDataQuery does not currently carry reportingYearStartDay. Placeholder: when
     * the data path learns the field, thread it through here too so the availability probe
     * reflects the same cube slice as the data call.
     */
    private static String extractReportingYearStartDay(TranslatedDataQuery query) {
        return null;
    }

    static List<String> nonTimeDimensionIds(SdmxBeans sdmxBeans) {
        return nonTimeDimensionIds(firstDsd(sdmxBeans));
    }

    // ===================== Bisect orchestration (version-agnostic) =====================

    private static List<String> nonTimeDimensionIds(DataStructureBean dsd) {
        return dsd.getDimensionList().getDimensions().stream()
                .filter(d -> !d.isTimeDimension())
                .map(IdentifiableBean::getId)
                .toList();
    }

    private static DataStructureBean firstDsd(SdmxBeans sdmxBeans) {
        return sdmxBeans.getDataStructures().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No DataStructure available for availability probe"));
    }

    // ===================== SDMX 3.0 =====================

    /**
     * Returns non-time dim ids in DSD order, reversed: last non-time dim first. Reasoning:
     * SDMX convention puts the most granular dim at the end of the DSD (e.g. FREQ first,
     * REF_AREA last). Cutting the last dim first is what an analyst typically does by hand
     * -- preserves the broad-dim semantics of the query and only samples the granular
     * tail. Choice is independent of cardinality; ties are deterministic via DSD position.
     */
    private static List<String> dimensionOrderForBisect(SdmxBeans sdmxBeans) {
        List<DimensionBean> all = firstDsd(sdmxBeans).getDimensionList().getDimensions();
        List<String> result = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) {
            DimensionBean dim = all.get(i);
            if (dim.isTimeDimension()) {
                continue;
            }
            result.add(dim.getId());
        }
        return result;
    }

    private static MultiValueMap<String, String> deepCopyFilters(MultiValueMap<String, String> src) {
        LinkedMultiValueMap<String, String> copy = new LinkedMultiValueMap<>();
        if (src != null) {
            src.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        }
        return copy;
    }

    private static void logProjection(String label, AvailabilityProjection projection, long m, long target) {
        if (log.isInfoEnabled()) {
            LinkedHashMap<String, Integer> sizes = new LinkedHashMap<>();
            projection.valuesByDimensionId().forEach((k, v) -> sizes.put(k, v.size()));
            log.info(
                    "{}: M={} (seriesCountAnnotation={}), target={}, dims={}, shrinkNeeded={}",
                    label,
                    m,
                    projection.seriesCount(),
                    target,
                    sizes,
                    m > target
            );
        }
    }

    // ===================== SDMX 2.1 =====================

    private static String registryName(TranslatedDataQuery query) {
        return query.getRegistryConfiguration() != null
                && query.getRegistryConfiguration().getName() != null
                ? query.getRegistryConfiguration().getName()
                : "unknown";
    }

    @Override
    public TranslatedDataQuery getShrunkQuery(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            AvailabilityProber prober
    ) {
        String registry = registryName(query);
        DataEndpointConfiguration cfg = query.getVersionConfiguration().getDataEndpointConfig();
        int n = query.getLimit() == null ? 0 : query.getLimit();
        long target = (long) Math.floor((double) n * cfg.getLimitEmulationTolerance());
        int probeBudget = cfg.getLimitEmulationProbeBudget();

        String timeDimId = dimensionService.getTimeDimensionId(
                sdmxBeans, query.getAgencyID(), query.getResourceID(), query.getVersion());

        SdmxVersion version = query.getVersionConfiguration().getSdmxVersion();
        log.info(
                "Limit emulation engaged: registry={}, sdmxVersion={}, agency={}, resource={}, version={}, "
                        + "limit={}, tolerance={}, target={}, probeBudget={}, timeDim={}",
                registry, version, query.getAgencyID(), query.getResourceID(), query.getVersion(),
                n, cfg.getLimitEmulationTolerance(), target, probeBudget, timeDimId
        );

        long tc = computeTotalCombinations(query, sdmxBeans, version, timeDimId);
        if (tc != -1L && tc <= target) {
            log.info(
                    "TC fast-path hit: registry={}, TC={}, target={}, skipping availability probe",
                    registry, tc, target
            );
            return query.toBuilder().limit(null).build();
        }

        BisectResult result = version == SdmxVersion.SDMX_2_1
                ? runBisect21(query, sdmxBeans, n, target, probeBudget, timeDimId, registry, prober)
                : runBisect30(query, sdmxBeans, n, target, probeBudget, timeDimId, registry, prober);

        log.info(
                "Limit emulation complete: registry={}, sdmxVersion={}, probes={}, finalM={}, target={}, "
                        + "shrunkKey='{}', shrunkFilters={}",
                registry, version, result.probesIssued, result.finalM, target,
                result.shrunkQuery.getKey(), result.shrunkQuery.getFilters()
        );

        return result.shrunkQuery;
    }

    /**
     * Combinatorial product of dim sizes -- client filter size when narrowed, DSD codelist
     * size otherwise. Returns {@code -1} when any non-time dim's codelist is unresolvable;
     * the caller then falls through to the probe path.
     */
    private long computeTotalCombinations(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            SdmxVersion version,
            String timeDimId
    ) {
        DataStructureBean dsd = firstDsd(sdmxBeans);
        Map<String, List<String>> keyState = version == SdmxVersion.SDMX_2_1
                ? keyParser.parseKey(query.getKey(), nonTimeDimensionIds(dsd))
                : Collections.emptyMap();
        MultiValueMap<String, String> filters = query.getFilters();

        long product = 1L;
        for (DimensionBean dim : dsd.getDimensionList().getDimensions()) {
            if (dim.isTimeDimension() || Objects.equals(dim.getId(), timeDimId)) {
                continue;
            }
            int size = dimSizeForTcEstimate(dim, version, keyState, filters, sdmxBeans);
            if (size < 0) {
                return -1L;
            }
            if (size == 0) {
                return 0L;
            }
            try {
                product = Math.multiplyExact(product, (long) size);
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
        return product;
    }

    // ===================== Probe plumbing =====================

    private int dimSizeForTcEstimate(
            DimensionBean dim,
            SdmxVersion version,
            Map<String, List<String>> keyState,
            MultiValueMap<String, String> filters,
            SdmxBeans sdmxBeans
    ) {
        String dimId = dim.getId();
        if (version == SdmxVersion.SDMX_2_1) {
            List<String> values = keyState.get(dimId);
            if (values != null && !values.isEmpty()) {
                return values.size();
            }
        } else {
            if (filters != null) {
                List<String> values = filters.get(dimId);
                if (values != null && !values.isEmpty()) {
                    return values.size();
                }
            }
        }
        return codelistSizeResolver.resolveSize(dim, sdmxBeans).orElse(-1);
    }

    /**
     * Per-dim bisect: drives the inner k-bisect on a single dim. Mutates {@code state}.
     * Returns the sc observed at the locked {@code k} (or {@link Long#MIN_VALUE} when the
     * dim was reverted because no overshoot could be produced inside the probe budget).
     */
    private DimBisectResult bisectOneDim(
            String dimId,
            List<String> dimValues,
            Map<String, List<String>> state,
            long mAtEntry,
            int n,
            long target,
            int probeBudgetRemaining,
            String registry,
            Function<Map<String, List<String>>, Long> reprobe
    ) {
        int dimSize = dimValues.size();
        int kLow = 0;
        int kHigh = dimSize;
        int probesUsed = 0;
        // Implicit overshoot at k = dimSize: mAtEntry was measured with this dim at full size
        // (or its pre-bisect snapshot, which the caller passes through unchanged here), and we
        // only entered the outer loop when m > target.
        long lastOvershootSc = mAtEntry;

        if (probeBudgetRemaining <= 0) {
            return new DimBisectResult(0, DimOutcome.NO_OVERSHOOT_FOUND, mAtEntry);
        }

        int k = bisectCalculator.proportionalWarmStart(dimSize, mAtEntry, target);

        while (true) {
            state.put(dimId, new ArrayList<>(dimValues.subList(0, k)));
            long sc = reprobe.apply(state);
            probesUsed++;
            log.info(
                    "Bisect probe: registry={}, dim={}, k={}, kLow={}, kHigh={}, SC={}, target={}",
                    registry, dimId, k, kLow, kHigh, sc, target
            );

            if (sc >= n && sc <= target) {
                log.info("Bisect lock: registry={}, dim={}, k={}, reason=in-band", registry, dimId, k);
                return new DimBisectResult(probesUsed, DimOutcome.IN_BAND, sc);
            }
            if (sc > target) {
                kHigh = k;
                lastOvershootSc = sc;
            } else {
                kLow = k;
            }

            if (kHigh - kLow <= 1) {
                if (kHigh == dimSize) {
                    // Every probe was undershoot; locking at k=dimSize is equivalent to reverting.
                    log.warn(
                            "Bisect could not produce overshoot below full dim, reverting: "
                                    + "registry={}, dim={}, kLow={}, dimSize={}",
                            registry, dimId, kLow, dimSize
                    );
                    return new DimBisectResult(probesUsed, DimOutcome.NO_OVERSHOOT_FOUND, mAtEntry);
                }
                if (k != kHigh) {
                    if (probesUsed >= probeBudgetRemaining) {
                        state.put(dimId, new ArrayList<>(dimValues.subList(0, kHigh)));
                        log.warn(
                                "Bisect convergence reached but no budget to re-probe k_high: registry={}, "
                                        + "dim={}, kLow={}, kHigh={}, lastOvershootSc={} (state set without re-probe)",
                                registry, dimId, kLow, kHigh, lastOvershootSc
                        );
                        return new DimBisectResult(probesUsed, DimOutcome.BUDGET_EXHAUSTED, lastOvershootSc);
                    }
                    state.put(dimId, new ArrayList<>(dimValues.subList(0, kHigh)));
                    long reprobed = reprobe.apply(state);
                    probesUsed++;
                    lastOvershootSc = reprobed;
                    log.info(
                            "Bisect probe (relock at k_high): registry={}, dim={}, k={}, SC={}",
                            registry, dimId, kHigh, reprobed
                    );
                }
                log.info(
                        "Bisect lock: registry={}, dim={}, k={}, reason=integer-gap-or-saturated, SC={}",
                        registry, dimId, kHigh, lastOvershootSc
                );
                return new DimBisectResult(probesUsed, DimOutcome.LOCKED_AT_KHIGH, lastOvershootSc);
            }

            if (probesUsed >= probeBudgetRemaining) {
                if (kHigh == dimSize) {
                    // No real overshoot found within budget — caller reverts.
                    log.warn(
                            "Bisect probe budget exhausted with no overshoot: registry={}, dim={}, "
                                    + "kLow={}, dimSize={}",
                            registry, dimId, kLow, dimSize
                    );
                    return new DimBisectResult(probesUsed, DimOutcome.NO_OVERSHOOT_FOUND, mAtEntry);
                }
                if (k != kHigh) {
                    state.put(dimId, new ArrayList<>(dimValues.subList(0, kHigh)));
                }
                log.warn(
                        "Bisect probe budget exhausted: registry={}, dim={}, lockedAtKHigh={}, "
                                + "lastOvershootSc={}, target={}",
                        registry, dimId, kHigh, lastOvershootSc, target
                );
                return new DimBisectResult(probesUsed, DimOutcome.BUDGET_EXHAUSTED, lastOvershootSc);
            }

            k = bisectCalculator.proportionalReStep(k, sc, target, kLow, kHigh);
        }
    }

    private void revertDim(Map<String, List<String>> state, String dimId, List<String> snapshot) {
        if (snapshot == null) {
            state.remove(dimId);
        } else {
            state.put(dimId, new ArrayList<>(snapshot));
        }
    }

    private BisectResult runBisect30(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            int n,
            long target,
            int probeBudget,
            String timeDimId,
            String registry,
            AvailabilityProber prober
    ) {
        MultiValueMap<String, String> filters = deepCopyFilters(query.getFilters());
        Function<Map<String, List<String>>, Long> reprobe = state ->
                probe30(query, sdmxBeans, (MultiValueMap<String, String>) state, prober).effectiveSeriesCount();

        AvailabilityProjection projection = probe30(query, sdmxBeans, filters, prober);
        long m = projection.effectiveSeriesCount();
        int probesIssued = 1;
        logProjection("Probe #1", projection, m, target);

        if (m <= target) {
            return finalize30(filters, m, probesIssued, query);
        }

        for (String dimId : dimensionOrderForBisect(sdmxBeans)) {
            if (m <= target) {
                break;
            }
            int budgetLeft = probeBudget - probesIssued;
            if (budgetLeft <= 0) {
                log.warn(
                        "Limit emulation probe budget exhausted before dim={}: registry={}, lastM={}, target={}",
                        dimId, registry, m, target
                );
                break;
            }
            List<String> values = projection.valuesByDimensionId().get(dimId);
            if (values == null || values.size() <= 1) {
                continue;
            }
            List<String> snapshot = filters.get(dimId);

            DimBisectResult dimResult = bisectOneDim(
                    dimId, values, filters, m, n, target, budgetLeft, registry, reprobe);
            probesIssued += dimResult.probesUsed;

            switch (dimResult.outcome) {
                case IN_BAND:
                    return finalize30(filters, dimResult.lastSc, probesIssued, query);
                case LOCKED_AT_KHIGH:
                case BUDGET_EXHAUSTED:
                    m = dimResult.lastSc;
                    break;
                case NO_OVERSHOOT_FOUND:
                    revertDim(filters, dimId, snapshot);
                    break;
            }
            if (dimResult.outcome == DimOutcome.BUDGET_EXHAUSTED) {
                break;
            }
        }
        return finalize30(filters, m, probesIssued, query);
    }

    private AvailabilityProjection probe30(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            MultiValueMap<String, String> filters,
            AvailabilityProber prober
    ) {
        TranslatedAvailabilityQuery availQuery = toAvailabilityQuery30(query, filters, sdmxBeans);
        return doProbe(query, sdmxBeans, availQuery, filters, prober);
    }

    private BisectResult finalize30(
            MultiValueMap<String, String> filters,
            long finalM,
            int probesIssued,
            TranslatedDataQuery query
    ) {
        TranslatedDataQuery shrunk = query.toBuilder()
                .filters(filters)
                .limit(null)
                .build();
        return new BisectResult(probesIssued, finalM, shrunk);
    }

    private BisectResult runBisect21(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            int n,
            long target,
            int probeBudget,
            String timeDimId,
            String registry,
            AvailabilityProber prober
    ) {
        List<String> nonTimeDims = nonTimeDimensionIds(sdmxBeans);
        Map<String, List<String>> keyState = keyParser.parseKey(query.getKey(), nonTimeDims);
        DataEndpointConfiguration dataCfg = query.getVersionConfiguration().getDataEndpointConfig();
        boolean mergeAllWildcardData = dataCfg != null && dataCfg.isMergeAllWildcardKey();

        Function<Map<String, List<String>>, Long> reprobe = state ->
                probe21(query, sdmxBeans, state, nonTimeDims, prober).effectiveSeriesCount();

        AvailabilityProjection projection = probe21(query, sdmxBeans, keyState, nonTimeDims, prober);
        long m = projection.effectiveSeriesCount();
        int probesIssued = 1;
        logProjection("Probe #1 (2.1)", projection, m, target);

        if (m <= target) {
            return finalize21(query, keyState, nonTimeDims, mergeAllWildcardData, m, probesIssued);
        }

        for (String dimId : dimensionOrderForBisect(sdmxBeans)) {
            if (m <= target) {
                break;
            }
            int budgetLeft = probeBudget - probesIssued;
            if (budgetLeft <= 0) {
                log.warn(
                        "Limit emulation probe budget exhausted before dim={}: registry={}, lastM={}, target={}",
                        dimId, registry, m, target
                );
                break;
            }
            List<String> values = projection.valuesByDimensionId().get(dimId);
            if (values == null || values.size() <= 1) {
                continue;
            }
            List<String> snapshot = keyState.get(dimId);

            DimBisectResult dimResult = bisectOneDim(
                    dimId, values, keyState, m, n, target, budgetLeft, registry, reprobe);
            probesIssued += dimResult.probesUsed;

            switch (dimResult.outcome) {
                case IN_BAND:
                    return finalize21(
                            query, keyState, nonTimeDims, mergeAllWildcardData,
                            dimResult.lastSc, probesIssued
                    );
                case LOCKED_AT_KHIGH:
                case BUDGET_EXHAUSTED:
                    m = dimResult.lastSc;
                    break;
                case NO_OVERSHOOT_FOUND:
                    revertDim(keyState, dimId, snapshot);
                    break;
            }
            if (dimResult.outcome == DimOutcome.BUDGET_EXHAUSTED) {
                break;
            }
        }
        return finalize21(query, keyState, nonTimeDims, mergeAllWildcardData, m, probesIssued);
    }

    private AvailabilityProjection probe21(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            Map<String, List<String>> keyState,
            List<String> nonTimeDims,
            AvailabilityProber prober
    ) {
        AvailabilityEndpointConfiguration availabilityConfig =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        boolean mergeAllWildcardAvail = availabilityConfig != null && availabilityConfig.isMergeAllWildcardKey();
        String key = keyParser.buildKey(keyState, nonTimeDims, mergeAllWildcardAvail);
        TranslatedAvailabilityQuery availQuery = toAvailabilityQuery21(query, sdmxBeans, key);
        return doProbe(query, sdmxBeans, availQuery, null, prober);
    }

    private BisectResult finalize21(
            TranslatedDataQuery query,
            Map<String, List<String>> keyState,
            List<String> nonTimeDims,
            boolean mergeAllWildcardData,
            long finalM,
            int probesIssued
    ) {
        String newKey = keyParser.buildKey(keyState, nonTimeDims, mergeAllWildcardData);
        TranslatedDataQuery shrunk = query.toBuilder()
                .key(newKey)
                .filters(null)
                .limit(null)
                .build();
        return new BisectResult(probesIssued, finalM, shrunk);
    }

    private AvailabilityProjection doProbe(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            TranslatedAvailabilityQuery availQuery,
            MultiValueMap<String, String> filtersForLog,
            AvailabilityProber prober
    ) {
        AvailabilityEndpointConfiguration availabilityConfig =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        log.debug(
                "Availability probe: key='{}', componentId={}, filters={}, format={}",
                availQuery.getKey(), availQuery.getComponentId(), filtersForLog, availQuery.getReturnFormat()
        );
        try (InputStream raw = prober.probe(availQuery);
             InputStream fixed = availabilityFixtureService.applyFixtures(
                     raw,
                     availQuery.getReturnFormat(),
                     sdmxBeans,
                     availabilityConfig != null ? availabilityConfig.getFixtures() : null
             )) {
            AvailabilityProjection projection = availabilityResponseParser.parse(fixed, availQuery.getReturnFormat());
            if (projection.valuesByDimensionId().isEmpty()) {
                log.warn(
                        "Availability parser returned EMPTY projection (registry={}, format={}). "
                                + "Bisect will fall through to fast path. Check that the parser "
                                + "understands the registry's response shape.",
                        registryName(query), availQuery.getReturnFormat()
                );
            }
            return projection;
        } catch (IOException e) {
            throw new IllegalStateException("Availability probe failed", e);
        }
    }

    private TranslatedAvailabilityQuery toAvailabilityQuery30(
            TranslatedDataQuery query,
            MultiValueMap<String, String> filters,
            SdmxBeans sdmxBeans
    ) {
        AvailabilityEndpointConfiguration availabilityConfig = requireAvailabilityConfig(query);
        String componentId = availabilityConfig.isUnwrapStarComponentId()
                ? String.join(",", nonTimeDimensionIds(sdmxBeans))
                : "*";
        ReturnFormat returnFormat = availabilityConfig.getDefaultFormat();
        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .context(query.getContext())
                .agencyID(query.getAgencyID())
                .resourceID(query.getResourceID())
                .version(query.getVersion())
                .key(query.getKey())
                .componentId(componentId)
                .filters(filters)
                .updatedAfter(query.getUpdatedAfter())
                .startPeriod(query.getStartPeriod())
                .endPeriod(query.getEndPeriod())
                .reportingYearStartDay(extractReportingYearStartDay(query))
                .mode("exact")
                .references("none")
                .returnFormat(returnFormat)
                .contentType(MediaType.parseMediaType(returnFormat.getContentType()))
                .build();
    }

    /**
     * 2.1 availability: positional key must be concrete, not {@code *}. {@code componentId}
     * must be the comma-joined list of non-time dims (SDMX-REST 2.1 availability spec does
     * not accept a bare {@code *} for componentId). No filters.
     */
    private TranslatedAvailabilityQuery toAvailabilityQuery21(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            String key
    ) {
        AvailabilityEndpointConfiguration availabilityConfig = requireAvailabilityConfig(query);
        String componentId = String.join(",", nonTimeDimensionIds(sdmxBeans));
        ReturnFormat returnFormat = availabilityConfig.getDefaultFormat();
        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .context(query.getContext())
                .agencyID(query.getAgencyID())
                .resourceID(query.getResourceID())
                .version(query.getVersion())
                .key(key)
                .componentId(componentId)
                .filters(null)
                .updatedAfter(query.getUpdatedAfter())
                .startPeriod(query.getStartPeriod())
                .endPeriod(query.getEndPeriod())
                .mode("exact")
                .references("none")
                .returnFormat(returnFormat)
                .contentType(MediaType.parseMediaType(returnFormat.getContentType()))
                .build();
    }

    // ===================== Internal types =====================

    private enum DimOutcome {
        IN_BAND,
        LOCKED_AT_KHIGH,
        NO_OVERSHOOT_FOUND,
        BUDGET_EXHAUSTED
    }

    private record DimBisectResult(int probesUsed, DimOutcome outcome, long lastSc) {
    }

    private record BisectResult(int probesIssued, long finalM, TranslatedDataQuery shrunkQuery) {
    }
}
