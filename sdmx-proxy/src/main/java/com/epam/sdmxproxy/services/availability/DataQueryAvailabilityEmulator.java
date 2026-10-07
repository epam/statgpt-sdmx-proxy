package com.epam.sdmxproxy.services.availability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityAsyncRetryConfig;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.epam.sdmxproxy.configuration.data.availability.UnfilteredAvailabilitySource;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.exception.AvailabilityProbeQueuedException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.availability.harvest.HarvestLimits;
import com.epam.sdmxproxy.services.availability.harvest.HarvestedCoverage;
import com.epam.sdmxproxy.services.availability.harvest.QueuedResponseDetector;
import com.epam.sdmxproxy.services.availability.harvest.SeriesKeyHarvester;
import com.epam.sdmxproxy.services.availability.harvest.SeriesKeyHarvesterProvider;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Emulates availability from a series-key data query -- see design 040.
 * <p>
 * Routes on one question: does the request narrow anything?
 * <ul>
 *   <li><strong>Nothing narrowed</strong> -- may be served from the registry's {@code Actual}
 *       content constraint, which answers exactly that request for 5.6--40 KB instead of the
 *       1.8--139 MB an equivalent probe costs.</li>
 *   <li><strong>Anything narrowed</strong> -- always probed, with the client's key and filters
 *       carried through unchanged. Never served from the constraint, not even when the probe
 *       fails: the constraint ignores narrowing, so that answer would report the whole cube as if
 *       it had been narrowed. It is a superset, so nothing would fail loudly -- it would just make
 *       every narrowing decision downstream wrong. A narrowed request that cannot be probed
 *       fails.</li>
 * </ul>
 * Both branches render through {@link AvailabilityConstraintSynthesizer} and the shared writer,
 * so the two responses are structurally identical -- which is what makes the consumer's
 * "filtered is a subset of unfiltered" invariant checkable rather than special-cased.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataQueryAvailabilityEmulator implements AvailabilityEmulator {

    private final AvailabilityQueryCanonicalizer canonicalizer;
    private final UnfilteredConstraintSource unfilteredConstraintSource;
    private final ProbeQueryBuilder probeQueryBuilder;
    private final ProbeDecomposer probeDecomposer;
    private final SeriesKeyHarvesterProvider harvesterProvider;
    private final QueuedResponseDetector queuedResponseDetector;
    private final AvailabilityConstraintSynthesizer synthesizer;
    private final StreamingAvailabilityConversionService conversionService;

    @Override
    public AvailabilityEmulationType supportedType() {
        return AvailabilityEmulationType.DATA_QUERY;
    }

    @Override
    public byte[] emulate(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityEmulationContext context
    ) {
        AvailabilityEmulationConfiguration emulation = probeQueryBuilder.emulation(query);
        boolean unfiltered = canonicalizer.isUnfiltered(query.getKey(), query.getFilters());

        HarvestedCoverage coverage;
        if (unfiltered && emulation.getUnfilteredSource() == UnfilteredAvailabilitySource.CONTENT_CONSTRAINT) {
            coverage = unfilteredConstraintSource.coverage(query, beans, context);
        } else {
            coverage = probeCoverage(query, beans, context, emulation);
            if (!unfiltered && emulation.getUnfilteredSource() == UnfilteredAvailabilitySource.CONTENT_CONSTRAINT) {
                coverage = intersectWithUniverse(query, beans, context, coverage);
            }
        }

        Artefacts artefacts = synthesizer.synthesize(query, beans, coverage);
        return render(artefacts, query);
    }

    /**
     * Runs the probe, decomposing and retrying as configured, and returns the coverage it yields.
     */
    private HarvestedCoverage probeCoverage(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityEmulationContext context,
            AvailabilityEmulationConfiguration emulation
    ) {
        SdmxFormat probeFormat = probeQueryBuilder.resolveProbeFormat(query, emulation);
        SeriesKeyHarvester harvester = harvesterProvider.forFormat(probeFormat);
        HarvestLimits limits = HarvestLimits.from(emulation);

        TranslatedDataQuery probe = probeQueryBuilder.build(query);
        try {
            return runProbe(probe, beans, harvester, limits, context, emulation, describe(query));
        } catch (ResponseTooLargeException | AvailabilityProbeQueuedException e) {
            log.info("Probe for {} failed with '{}', attempting decomposition", describe(query), e.getMessage());
            return decomposedProbe(query, beans, context, emulation, harvester, limits, e);
        }
    }

    /**
     * Splits the probe by key and unions the chunk harvests. Rethrows the original failure when
     * no split is possible or the fan-out budget cannot cover it -- the caller must not fall back
     * to the constraint for a narrowed request.
     */
    private HarvestedCoverage decomposedProbe(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityEmulationContext context,
            AvailabilityEmulationConfiguration emulation,
            SeriesKeyHarvester harvester,
            HarvestLimits limits,
            RuntimeException original
    ) {
        Optional<List<ProbeDecomposer.Chunk>> plan = probeDecomposer.plan(
                query, beans, canonicalizer,
                emulation.getProbeSplitChunkSize(), emulation.getMaxProbeFanOut());
        if (plan.isEmpty()) {
            throw original;
        }

        HarvestedCoverage merged = HarvestedCoverage.empty();
        for (ProbeDecomposer.Chunk chunk : plan.get()) {
            ProbeDecomposer.PinnedNarrowing pinned =
                    probeDecomposer.pin(query, beans, chunk, query.getFilters());
            TranslatedDataQuery chunkProbe =
                    probeQueryBuilder.build(query, pinned.key(), pinned.filters());
            HarvestedCoverage chunkCoverage = runProbe(
                    chunkProbe, beans, harvester, limits, context, emulation,
                    describe(query) + " chunk " + chunk.dimensionId() + "=" + chunk.values());
            merged = merged.merge(chunkCoverage);
        }
        log.info("Decomposed probe for {} harvested {} series in total", describe(query), merged.seriesCount());
        return merged;
    }

    /**
     * Issues one probe, retrying while the registry answers with a queued-extraction envelope.
     * <p>
     * Re-issuing the identical URL once the upstream job completes is what returns the payload,
     * so the retry is of the same request rather than a poll of an asynchronous API.
     */
    private HarvestedCoverage runProbe(
            TranslatedDataQuery probe,
            SdmxBeans beans,
            SeriesKeyHarvester harvester,
            HarvestLimits limits,
            AvailabilityEmulationContext context,
            AvailabilityEmulationConfiguration emulation,
            String description
    ) {
        AvailabilityAsyncRetryConfig retry = emulation.getAsyncRetry() == null
                ? new AvailabilityAsyncRetryConfig()
                : emulation.getAsyncRetry();
        int maxAttempts = retry.isEnabled() ? Math.max(1, retry.getMaxAttempts()) : 1;
        long waited = 0L;
        long interval = retry.getInitialIntervalMillis();
        AvailabilityProbeQueuedException lastQueued = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (InputStream raw = context.fetchData(probe)) {
                if (raw == null) {
                    throw new AvailabilityEmulationException(
                            "Registry returned no body for the availability emulation probe for " + description);
                }
                InputStream checked = queuedResponseDetector.requireNotQueued(raw, description);
                return harvester.harvest(checked, beans, limits);
            } catch (AvailabilityProbeQueuedException e) {
                lastQueued = e;
                if (attempt == maxAttempts) {
                    break;
                }
                long sleep = Math.min(interval, Math.max(0L, retry.getMaxTotalWaitMillis() - waited));
                if (sleep <= 0L) {
                    break;
                }
                log.info("Probe for {} is queued, retrying in {} ms (attempt {}/{})",
                        description, sleep, attempt, maxAttempts);
                sleepQuietly(sleep);
                waited += sleep;
                interval = (long) (interval * retry.getMultiplier());
            } catch (IOException e) {
                throw new AvailabilityEmulationException(
                        "Failed to read the availability emulation probe for " + description, e);
            }
        }

        throw new AvailabilityProbeQueuedException(
                "Registry kept the availability emulation probe for " + description
                        + " queued for the whole retry budget", lastQueued);
    }

    /**
     * Narrows the harvest to values the registry's own constraint also reports.
     * <p>
     * Guards the consumer's subset invariant across the two sources. The dangerous direction is a
     * probe returning a code the constraint omits: that code has no entry in the load-time
     * response the consumer indexed, so looking it up raises. Measured divergence on Eurostat is
     * zero in both directions, which is why a difference here is logged as a warning -- it means
     * the two resources have drifted, and that is worth knowing.
     */
    private HarvestedCoverage intersectWithUniverse(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityEmulationContext context,
            HarvestedCoverage harvested
    ) {
        HarvestedCoverage universe;
        try {
            universe = unfilteredConstraintSource.coverage(query, beans, context);
        } catch (RuntimeException e) {
            // The probe already answered the client's question. Failing it because the
            // consistency guard could not run would trade a correct answer for none.
            log.warn("Could not load the constraint universe for {}; returning the probe harvest unintersected: {}",
                    describe(query), e.getMessage());
            return harvested;
        }

        Map<String, Set<String>> intersected = new LinkedHashMap<>();
        int dropped = 0;
        for (Map.Entry<String, Set<String>> entry : universe.valuesByDimensionId().entrySet()) {
            String dimensionId = entry.getKey();
            Set<String> allowed = entry.getValue();
            Set<String> observed = harvested.valuesByDimensionId().getOrDefault(dimensionId, Set.of());
            Set<String> kept = new LinkedHashSet<>();
            for (String value : allowed) {
                if (observed.contains(value)) {
                    kept.add(value);
                }
            }
            dropped += observed.size() - kept.size();
            intersected.put(dimensionId, kept);
        }

        if (dropped > 0) {
            log.warn("Probe for {} reported {} value(s) the registry's constraint does not list; dropped to keep "
                    + "the filtered response a subset of the unfiltered one", describe(query), dropped);
        }
        return new HarvestedCoverage(intersected, harvested.seriesCount());
    }

    private byte[] render(Artefacts artefacts, TranslatedAvailabilityQuery query) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.write(artefacts, out, query.getContentType());
        return out.toByteArray();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AvailabilityProbeQueuedException("Interrupted while waiting for a queued probe", e);
        }
    }

    private static String describe(TranslatedAvailabilityQuery query) {
        return query.getAgencyID() + ":" + query.getResourceID() + "(" + query.getVersion() + ")"
                + " key='" + query.getKey() + "'";
    }
}
