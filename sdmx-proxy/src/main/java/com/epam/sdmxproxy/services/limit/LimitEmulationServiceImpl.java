package com.epam.sdmxproxy.services.limit;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitEmulationServiceImpl implements LimitEmulationService {

    private final AvailabilityFixtureService availabilityFixtureService;
    private final AvailabilityResponseParser availabilityResponseParser;
    private final FilterShrinker filterShrinker;
    private final DimensionService dimensionService;
    private final KeyParser keyParser;

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
        int maxIterations = cfg.getLimitEmulationMaxShrinkIterations();

        String timeDimId = dimensionService.getTimeDimensionId(
                sdmxBeans, query.getAgencyID(), query.getResourceID(), query.getVersion());

        SdmxVersion version = query.getVersionConfiguration().getSdmxVersion();
        log.info("Limit emulation engaged: registry={}, sdmxVersion={}, agency={}, resource={}, version={}, "
                        + "limit={}, tolerance={}, target={}, maxIterations={}, timeDim={}",
                registry, version, query.getAgencyID(), query.getResourceID(), query.getVersion(),
                n, cfg.getLimitEmulationTolerance(), target, maxIterations, timeDimId);

        ShrinkResult result = version == SdmxVersion.SDMX_2_1
                ? runShrinkLoop21(query, sdmxBeans, target, maxIterations, timeDimId, registry, prober)
                : runShrinkLoop30(query, sdmxBeans, target, maxIterations, timeDimId, registry, prober);

        log.info("Shrink loop complete: registry={}, sdmxVersion={}, probes={}, iterations={}, finalM={}, target={}, "
                        + "shrunkKey='{}', shrunkFilters={}",
                registry, version, result.probesIssued, result.iterations, result.finalM, target,
                result.shrunkQuery.getKey(), result.shrunkQuery.getFilters());

        return result.shrunkQuery;
    }

    /**
     * SDMX 3.0 shrink loop: mutates a {@link MultiValueMap} of filters. Client key flows
     * through unchanged; shrink decisions OR-join values via {@code c[X]=A,B} filters.
     */
    private ShrinkResult runShrinkLoop30(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            long target,
            int maxIterations,
            String timeDimId,
            String registry,
            AvailabilityProber prober
    ) {
        MultiValueMap<String, String> filters = deepCopyFilters(query.getFilters());
        AvailabilityProjection projection = probeAvailability30(query, filters, sdmxBeans, prober);
        int probesIssued = 1;
        long m = projection.effectiveSeriesCount();
        logProjection("Probe #1", projection, m, target);
        int iterations = 0;

        while (m > target) {
            if (iterations >= maxIterations) {
                log.warn("Limit emulation shrink cap reached: registry={}, iterations={}, M={}, target={}",
                        registry, iterations, m, target);
                break;
            }
            ShrinkDecision decision = filterShrinker.decide(projection, target, timeDimId);
            if (decision.isNone()) {
                log.info("Shrink loop exit: no productive shrink possible (registry={}, M={}, target={})",
                        registry, m, target);
                break;
            }
            iterations++;
            log.info("Shrink iteration {}: narrow dim '{}' from {} to {} values (retained: {})",
                    iterations,
                    decision.getDimensionId(),
                    sizeOfDim(projection, decision.getDimensionId()),
                    decision.getRetainedValues().size(),
                    previewValues(decision.getRetainedValues()));
            filters.put(decision.getDimensionId(), new ArrayList<>(decision.getRetainedValues()));
            projection = probeAvailability30(query, filters, sdmxBeans, prober);
            probesIssued++;
            m = projection.effectiveSeriesCount();
            logProjection("Probe #" + probesIssued, projection, m, target);
        }

        TranslatedDataQuery shrunkQuery = query.toBuilder()
                .filters(filters)
                .limit(null)
                .build();
        return new ShrinkResult(probesIssued, iterations, m, shrunkQuery);
    }

    /**
     * SDMX 2.1 shrink loop: client {@code c[]} filters have already been merged into the
     * positional key by {@link com.epam.sdmxproxy.services.translator.QueryTranslatorImpl},
     * and 2.1 Feign clients don't read {@code filters}. We parse the key, shrink in the
     * key map, rebuild, and pass {@code filters=null} downstream.
     */
    private ShrinkResult runShrinkLoop21(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            long target,
            int maxIterations,
            String timeDimId,
            String registry,
            AvailabilityProber prober
    ) {
        List<String> nonTimeDims = nonTimeDimensionIds(sdmxBeans);
        Map<String, List<String>> keyState = keyParser.parseKey(query.getKey(), nonTimeDims);
        DataEndpointConfiguration dataCfg = query.getVersionConfiguration().getDataEndpointConfig();
        boolean mergeAllWildcardData = dataCfg != null && dataCfg.isMergeAllWildcardKey();

        AvailabilityProjection projection = probeAvailability21(query, sdmxBeans, keyState, nonTimeDims, prober);
        int probesIssued = 1;
        long m = projection.effectiveSeriesCount();
        logProjection("Probe #1 (2.1)", projection, m, target);
        int iterations = 0;

        while (m > target) {
            if (iterations >= maxIterations) {
                log.warn("Limit emulation shrink cap reached: registry={}, iterations={}, M={}, target={}",
                        registry, iterations, m, target);
                break;
            }
            ShrinkDecision decision = filterShrinker.decide(projection, target, timeDimId);
            if (decision.isNone()) {
                log.info("Shrink loop exit: no productive shrink possible (registry={}, M={}, target={})",
                        registry, m, target);
                break;
            }
            iterations++;
            log.info("Shrink iteration {} (2.1): narrow key position '{}' from {} to {} values (retained: {})",
                    iterations,
                    decision.getDimensionId(),
                    sizeOfDim(projection, decision.getDimensionId()),
                    decision.getRetainedValues().size(),
                    previewValues(decision.getRetainedValues()));
            keyState.put(decision.getDimensionId(), new ArrayList<>(decision.getRetainedValues()));
            projection = probeAvailability21(query, sdmxBeans, keyState, nonTimeDims, prober);
            probesIssued++;
            m = projection.effectiveSeriesCount();
            logProjection("Probe #" + probesIssued + " (2.1)", projection, m, target);
        }

        String newKey = keyParser.buildKey(keyState, nonTimeDims, mergeAllWildcardData);
        TranslatedDataQuery shrunkQuery = query.toBuilder()
                .key(newKey)
                .filters(null)
                .limit(null)
                .build();
        return new ShrinkResult(probesIssued, iterations, m, shrunkQuery);
    }

    private AvailabilityProjection probeAvailability30(
            TranslatedDataQuery query,
            MultiValueMap<String, String> filters,
            SdmxBeans sdmxBeans,
            AvailabilityProber prober
    ) {
        TranslatedAvailabilityQuery availQuery = toAvailabilityQuery30(query, filters, sdmxBeans);
        return doProbe(query, sdmxBeans, availQuery, filters, prober);
    }

    private AvailabilityProjection probeAvailability21(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            Map<String, List<String>> keyState,
            List<String> nonTimeDims,
            AvailabilityProber prober
    ) {
        AvailabilityEndpointConfiguration availCfg =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        boolean mergeAllWildcardAvail = availCfg != null && availCfg.isMergeAllWildcardKey();
        String key = keyParser.buildKey(keyState, nonTimeDims, mergeAllWildcardAvail);
        TranslatedAvailabilityQuery availQuery = toAvailabilityQuery21(query, sdmxBeans, key);
        return doProbe(query, sdmxBeans, availQuery, null, prober);
    }

    private AvailabilityProjection doProbe(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            TranslatedAvailabilityQuery availQuery,
            MultiValueMap<String, String> filtersForLog,
            AvailabilityProber prober
    ) {
        AvailabilityEndpointConfiguration availCfg =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        log.debug("Availability probe: key='{}', componentId={}, filters={}, format={}",
                availQuery.getKey(), availQuery.getComponentId(), filtersForLog, availQuery.getReturnFormat());
        try (InputStream raw = prober.probe(availQuery);
             InputStream fixed = availabilityFixtureService.applyFixtures(
                     raw,
                     availQuery.getReturnFormat(),
                     sdmxBeans,
                     availCfg != null ? availCfg.getFixtures() : null
             )) {
            AvailabilityProjection projection = availabilityResponseParser.parse(fixed, availQuery.getReturnFormat());
            if (projection.valuesByDimensionId().isEmpty()) {
                log.warn("Availability parser returned EMPTY projection (registry={}, format={}). "
                                + "Shrink loop will fall through to fast path. Check that the parser "
                                + "understands the registry's response shape.",
                        registryName(query), availQuery.getReturnFormat());
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
        AvailabilityEndpointConfiguration availCfg = requireAvailabilityConfig(query);
        String componentId = availCfg.isUnwrapStarComponentId()
                ? String.join(",", nonTimeDimensionIds(sdmxBeans))
                : "*";
        ReturnFormat returnFormat = availCfg.getDefaultFormat();
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
        AvailabilityEndpointConfiguration availCfg = requireAvailabilityConfig(query);
        String componentId = String.join(",", nonTimeDimensionIds(sdmxBeans));
        ReturnFormat returnFormat = availCfg.getDefaultFormat();
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

    private static AvailabilityEndpointConfiguration requireAvailabilityConfig(TranslatedDataQuery query) {
        AvailabilityEndpointConfiguration availCfg =
                query.getVersionConfiguration().getAvailabilityEndpointConfig();
        if (availCfg == null) {
            throw new IllegalStateException(
                    "availabilityEndpointConfig is required for supportsLimit=false registries");
        }
        return availCfg;
    }

    /**
     * TranslatedDataQuery does not currently carry reportingYearStartDay. Placeholder: when
     * the data path learns the field, thread it through here too so the availability probe
     * reflects the same cube slice as the data call.
     */
    private static String extractReportingYearStartDay(TranslatedDataQuery query) {
        return null;
    }

    static List<String> nonTimeDimensionIds(SdmxBeans sdmxBeans) {
        DataStructureBean dsd = sdmxBeans.getDataStructures().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No DataStructure available for availability probe"));
        return dsd.getDimensionList().getDimensions().stream()
                .filter(d -> !d.isTimeDimension())
                .map(IdentifiableBean::getId)
                .toList();
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
            log.info("{}: M={} (seriesCountAnnotation={}), target={}, dims={}, shrinkNeeded={}",
                    label,
                    m,
                    projection.seriesCount(),
                    target,
                    sizes,
                    m > target);
        }
    }

    private static int sizeOfDim(AvailabilityProjection projection, String dimensionId) {
        List<String> values = projection.valuesByDimensionId().get(dimensionId);
        return values == null ? 0 : values.size();
    }

    private static String previewValues(List<String> values) {
        if (values.size() <= 10) {
            return values.toString();
        }
        return values.subList(0, 10) + " ... (+" + (values.size() - 10) + " more)";
    }

    private static String registryName(TranslatedDataQuery query) {
        return query.getRegistryConfiguration() != null
                && query.getRegistryConfiguration().getName() != null
                ? query.getRegistryConfiguration().getName()
                : "unknown";
    }

    /** Bundle of loop results handed back to the entry method. */
    private record ShrinkResult(
            int probesIssued,
            int iterations,
            long finalM,
            TranslatedDataQuery shrunkQuery
    ) {
    }
}
