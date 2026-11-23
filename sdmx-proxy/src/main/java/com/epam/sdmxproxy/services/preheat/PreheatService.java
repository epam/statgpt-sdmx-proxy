package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.common.utils.FormatSupportChecker;
import com.epam.sdmxproxy.configuration.data.PreheatConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.exception.TooManyRequestsException;
import com.epam.sdmxproxy.registry.api.http.Sleeper;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationChangedEvent;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.adapter.RawStructureFetcher;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import feign.FeignException;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Keeps the dataflows listed in the root {@code preheat} configuration warm in the structure
 * caches, and splits their descendants into ready responses for the configured media type. Runs
 * on one background thread at startup, after configuration changes, and periodically. See design
 * 044.
 */
@Slf4j
@Service
public class PreheatService {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String DATAFLOW = "dataflow";
    private static final String CODELIST = "codelist";
    private static final String CONCEPT_SCHEME = "conceptscheme";
    private static final String FULL = "full";
    private static final String WARMED = "warmed";
    private static final String SKIPPED = "skipped";
    private static final String FAILED = "failed";
    private static final String UNKNOWN_REGISTRY = "unknown";
    private static final AttributeKey<String> REGISTRY = AttributeKey.stringKey("registry");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    private static final AttributeKey<String> TRIGGER = AttributeKey.stringKey("trigger");

    private final ProxyConfigurationProvider configurationProvider;
    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private final RawStructureFetcher rawStructureFetcher;
    private final CacheService cacheService;
    private final DescendantsSplitter descendantsSplitter;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;
    private final PreheatProperties properties;
    private final Sleeper sleeper;
    private final ScheduledExecutorService executor;
    private final LongSupplier nanoTime;

    private final AtomicLong generation = new AtomicLong();
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> pendingRun;
    private boolean periodicScheduled;
    private long lastUpstreamCallNanos;
    private boolean upstreamCalled;

    private final LongCounter runsCounter;
    private final LongCounter runFailuresCounter;
    private final LongCounter dataflowsCounter;
    private final LongCounter upstreamCallsCounter;
    private final LongCounter refreshesDeferredCounter;
    private final LongCounter splitEntriesCounter;
    private final LongCounter splitSkippedCounter;

    @Autowired
    public PreheatService(ProxyConfigurationProvider configurationProvider, QueryTranslator queryTranslator, AdapterRouter adapterRouter, RawStructureFetcher rawStructureFetcher, CacheService cacheService, DescendantsSplitter descendantsSplitter, Sdmx21QueryNormalizer sdmx21QueryNormalizer, PreheatProperties properties, CacheProperties cacheProperties, Sleeper sleeper, ObjectProvider<OpenTelemetry> openTelemetry) {
        this(configurationProvider, queryTranslator, adapterRouter, rawStructureFetcher, cacheService, descendantsSplitter, sdmx21QueryNormalizer, properties, cacheProperties, sleeper, openTelemetry, Executors.newSingleThreadScheduledExecutor(PreheatService::newPreheatThread), System::nanoTime);
    }

    PreheatService(ProxyConfigurationProvider configurationProvider, QueryTranslator queryTranslator, AdapterRouter adapterRouter, RawStructureFetcher rawStructureFetcher, CacheService cacheService, DescendantsSplitter descendantsSplitter, Sdmx21QueryNormalizer sdmx21QueryNormalizer, PreheatProperties properties, CacheProperties cacheProperties, Sleeper sleeper, ObjectProvider<OpenTelemetry> openTelemetry, ScheduledExecutorService executor, LongSupplier nanoTime) {
        properties.validate(cacheProperties.getTtl());
        this.configurationProvider = configurationProvider;
        this.queryTranslator = queryTranslator;
        this.adapterRouter = adapterRouter;
        this.rawStructureFetcher = rawStructureFetcher;
        this.cacheService = cacheService;
        this.descendantsSplitter = descendantsSplitter;
        this.sdmx21QueryNormalizer = sdmx21QueryNormalizer;
        this.properties = properties;
        this.sleeper = sleeper;
        this.executor = executor;
        this.nanoTime = nanoTime;
        Meter meter = openTelemetry.getIfAvailable(OpenTelemetry::noop).getMeter("sdmx-proxy.preheat");
        this.runsCounter = meter.counterBuilder("sdmxproxy.preheat.runs").setDescription("Preheat runs started").build();
        this.runFailuresCounter = meter.counterBuilder("sdmxproxy.preheat.run_failures").setDescription("Preheat runs that ended with an unexpected error").build();
        this.dataflowsCounter = meter.counterBuilder("sdmxproxy.preheat.dataflows").setDescription("Preheat dataflow outcomes").build();
        this.upstreamCallsCounter = meter.counterBuilder("sdmxproxy.preheat.upstream_calls").setDescription("Registry calls made by preheat").build();
        this.refreshesDeferredCounter = meter.counterBuilder("sdmxproxy.preheat.refreshes_deferred").setDescription("Refreshes postponed by the per-run budget").build();
        this.splitEntriesCounter = meter.counterBuilder("sdmxproxy.preheat.split_entries").setDescription("Ready responses written from descendants").build();
        this.splitSkippedCounter = meter.counterBuilder("sdmxproxy.preheat.split_skipped").setDescription("Artefacts not split because the split would differ from the live response").build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            log.info("Preheat is disabled (sdmxproxy.preheat.enabled=false)");
            return;
        }
        long intervalMillis = properties.getRefreshInterval().toMillis();
        synchronized (scheduleLock) {
            if (!periodicScheduled) {
                executor.scheduleWithFixedDelay(() -> run("periodic"), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
                periodicScheduled = true;
            }
        }
        scheduleOneShot("startup", properties.getStartDelay());
    }

    /**
     * Runs on the publishing thread (an HTTP or config-poll thread). Rescheduling happens here
     * under a lock rather than on the preheat executor, where it would queue behind a running run.
     */
    @EventListener(ProxyConfigurationChangedEvent.class)
    public void onConfigurationChanged(ProxyConfigurationChangedEvent event) {
        generation.incrementAndGet();
        if (!properties.isEnabled()) {
            return;
        }
        log.info("Preheat rescheduled after a configuration change from {} (caches invalidated: {})", event.source(), event.cachesInvalidated());
        scheduleOneShot("config-change", properties.getConfigChangeDebounce());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * One full pass over the configured dataflows. Never throws: a {@code scheduleWithFixedDelay}
     * task stops for good after its first exception.
     */
    void run(String trigger) {
        if (!properties.isEnabled()) {
            return;
        }
        RunState state = new RunState();
        long started = nanoTime.getAsLong();
        runsCounter.add(1, Attributes.of(TRIGGER, trigger));
        try {
            long runGeneration = generation.get();
            ProxyConfiguration configuration = configurationProvider.getConfiguration();
            List<PreheatConfiguration> groups = configuration == null ? null : configuration.getPreheat();
            for (PreheatConfiguration group : nullToEmpty(groups)) {
                if (group == null || group.getMediaType() == null || group.getMediaType().isBlank()) {
                    log.warn("Preheat group without a media type skipped");
                    continue;
                }
                for (String reference : nullToEmpty(group.getDataflows())) {
                    checkActive(runGeneration);
                    warmWithRetry(group.getMediaType(), reference, runGeneration, state);
                }
            }
        } catch (PreheatCancelledException e) {
            log.info("Preheat run stopped: {}", e.getMessage());
        } catch (RuntimeException e) {
            runFailuresCounter.add(1);
            log.error("Preheat run failed", e);
        }
        Duration took = Duration.ofNanos(nanoTime.getAsLong() - started);
        log.info("Preheat run ({}) finished in {}: {} warmed, {} skipped, {} failed, {} split entries, {} upstream calls, {} refreshes deferred", trigger, took, state.warmed, state.skipped, state.failed, state.splitEntries, state.upstreamCalls, state.refreshesDeferred);
    }

    private void scheduleOneShot(String trigger, Duration delay) {
        synchronized (scheduleLock) {
            if (pendingRun != null) {
                pendingRun.cancel(false);
            }
            pendingRun = executor.schedule(() -> run(trigger), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void warmWithRetry(String mediaType, String reference, long runGeneration, RunState state) {
        DataflowReference ref;
        try {
            ref = DataflowReference.parse(reference);
        } catch (IllegalArgumentException e) {
            log.warn("Preheat skips malformed dataflow reference: {}", e.getMessage());
            record(state, UNKNOWN_REGISTRY, FAILED);
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                Outcome outcome = warmDataflow(mediaType, ref, runGeneration, state);
                record(state, outcome.registry(), outcome.result());
                return;
            } catch (PreheatCancelledException e) {
                throw e;
            } catch (TooManyRequestsException e) {
                if (!backOffForRetry(attempt, ref, e)) {
                    record(state, UNKNOWN_REGISTRY, FAILED);
                    return;
                }
            } catch (FeignException e) {
                if (e.status() != TOO_MANY_REQUESTS || !backOffForRetry(attempt, ref, e)) {
                    log.warn("Preheat of {} failed: registry answered {}", ref, e.status());
                    record(state, UNKNOWN_REGISTRY, FAILED);
                    return;
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    // The coalescer reports an interrupt as UnexpectedStateException.
                    throw new PreheatCancelledException("preheat thread interrupted");
                }
                log.warn("Preheat of {} failed: {}", ref, e.getMessage(), e);
                record(state, UNKNOWN_REGISTRY, FAILED);
                return;
            }
        }
    }

    /**
     * @return true when another attempt should follow, false when the attempts are used up
     */
    private boolean backOffForRetry(int attempt, DataflowReference ref, RuntimeException cause) {
        if (attempt >= properties.getMaxAttemptsPerDataflow()) {
            log.warn("Preheat of {} gave up after {} rate-limited attempts: {}", ref, attempt, cause.getMessage());
            return false;
        }
        log.info("Preheat of {} rate-limited (attempt {}); retrying in {}", ref, attempt, properties.getRateLimitBackoff());
        if (!sleeper.sleep(properties.getRateLimitBackoff().toMillis())) {
            throw new PreheatCancelledException("preheat thread interrupted");
        }
        return true;
    }

    private Outcome warmDataflow(String mediaType, DataflowReference ref, long runGeneration, RunState state) {
        TranslatedStructureQuery flowQuery = queryTranslator.translateStructureQuery(DATAFLOW, ref.agency(), ref.id(), ref.version(), "datastructure", FULL, mediaType, null);
        String registry = flowQuery.getRegistryConfiguration().getName();
        StructureEndpointConfiguration endpoint = flowQuery.getVersionConfiguration().getStructureEndpointConfig();
        if (endpoint == null || !endpoint.isPreheatEnabled()) {
            return new Outcome(registry, SKIPPED);
        }
        ensureRaw(flowQuery, runGeneration, state);
        checkActive(runGeneration);
        // A hit is a no-op; an expired Q1 ready response is rebuilt from the warm raw entry.
        warmReadyResponse(flowQuery);
        DataflowBean flow = single(adapterRouter.getSdmxBeans(flowQuery).getDataflows(), ref);
        String dsdUrn = flow.getDataStructureRef().getTargetUrn();
        // Accept and source URN null, as in the availability controller's descendants call.
        TranslatedStructureQuery descendantsQuery = queryTranslator.translateStructureQuery(DATAFLOW, ref.agency(), ref.id(), flow.getVersion().toString(), "descendants", FULL, null, null);
        ensureRaw(descendantsQuery, runGeneration, state);
        SdmxBeans closure = adapterRouter.getSdmxBeans(descendantsQuery);
        if (closure.getConceptSchemes().isEmpty() && closure.getCodelists().isEmpty()) {
            throw new PreheatDataflowException("descendants answer for " + ref + " has no concept schemes and no codelists");
        }
        HeaderBean header = closure.getHeader();
        for (MaintainableBean scheme : closure.getConceptSchemes()) {
            split(CONCEPT_SCHEME, scheme, header, mediaType, dsdUrn, descendantsQuery, runGeneration, state);
        }
        for (MaintainableBean codelist : closure.getCodelists()) {
            split(CODELIST, codelist, header, mediaType, dsdUrn, descendantsQuery, runGeneration, state);
        }
        return new Outcome(registry, WARMED);
    }

    private void split(String type, MaintainableBean artefact, HeaderBean header, String mediaType, String dsdUrn, TranslatedStructureQuery descendantsQuery, long runGeneration, RunState state) {
        checkActive(runGeneration);
        TranslatedStructureQuery pointQuery = queryTranslator.translateStructureQuery(type, artefact.getAgencyId(), artefact.getId(), artefact.getVersion().toString(), null, FULL, mediaType, dsdUrn);
        if (FormatSupportChecker.canBypassStructureFormat(pointQuery.getVersionConfiguration(), pointQuery.getContentType())) {
            skipSplit(pointQuery, "bypass");
            return;
        }
        if (!sameEndpoint(pointQuery, descendantsQuery)) {
            skipSplit(pointQuery, "endpoint");
            return;
        }
        if (readyResponseNeedsBuild(pointQuery)) {
            descendantsSplitter.store(pointQuery, artefact, header);
            state.splitEntries++;
            splitEntriesCounter.add(1, Attributes.of(REGISTRY, pointQuery.getRegistryConfiguration().getName()));
        }
    }

    private void skipSplit(TranslatedStructureQuery pointQuery, String reason) {
        String registry = pointQuery.getRegistryConfiguration().getName();
        log.warn("Preheat does not split {} for registry {}: {}", pointQuery.getStructure(), registry, "bypass".equals(reason) ? "the media type is served by bypass" : "the point request resolves to another endpoint than the descendants request");
        splitSkippedCounter.add(1, Attributes.of(REGISTRY, registry, REASON, reason));
    }

    /**
     * Calls the registry, paced, only when the raw entry is missing, or near expiry and within the
     * run's refresh budget.
     */
    private void ensureRaw(TranslatedStructureQuery query, long runGeneration, RunState state) {
        String key = CacheKeyGenerator.generateStructureKey(query, sdmx21QueryNormalizer);
        Optional<Duration> ttl = cacheService.getRawStructuresTimeToLive(key);
        if (ttl.isPresent() && ttl.get().compareTo(refreshThreshold(key)) >= 0) {
            return;
        }
        String registry = query.getRegistryConfiguration().getName();
        if (ttl.isPresent() && state.refreshes >= properties.getMaxRefreshesPerRun()) {
            state.refreshesDeferred++;
            refreshesDeferredCounter.add(1, Attributes.of(REGISTRY, registry));
            return;
        }
        pace(runGeneration);
        state.upstreamCalls++;
        upstreamCallsCounter.add(1, Attributes.of(REGISTRY, registry));
        if (ttl.isEmpty()) {
            rawStructureFetcher.fetch(query);
        } else {
            state.refreshes++;
            rawStructureFetcher.refresh(query);
        }
    }

    /**
     * {@code refreshAhead} plus a stable per-key offset in {@code [0, refreshSpread)}, so entries
     * written together are refreshed in different runs and stay apart.
     */
    Duration refreshThreshold(String key) {
        long spreadMinutes = Math.max(1, properties.getRefreshSpread().toMinutes());
        return properties.getRefreshAhead().plusMinutes(Math.floorMod(key.hashCode(), spreadMinutes));
    }

    private boolean readyResponseNeedsBuild(TranslatedStructureQuery query) {
        Optional<Duration> ttl = cacheService.getReadyResponseTimeToLive(descendantsSplitter.readyResponseKey(query));
        return ttl.isEmpty() || ttl.get().compareTo(properties.getReadyRefreshAhead()) < 0;
    }

    private static boolean sameEndpoint(TranslatedStructureQuery a, TranslatedStructureQuery b) {
        StructureEndpointConfiguration endpointA = a.getVersionConfiguration().getStructureEndpointConfig();
        StructureEndpointConfiguration endpointB = b.getVersionConfiguration().getStructureEndpointConfig();
        String urlA = endpointA == null ? null : endpointA.getUrl();
        String urlB = endpointB == null ? null : endpointB.getUrl();
        return Objects.equals(a.getRegistryConfiguration().getName(), b.getRegistryConfiguration().getName()) && a.getVersionConfiguration().getSdmxVersion() == b.getVersionConfiguration().getSdmxVersion() && Objects.equals(urlA, urlB) && a.getRegistryReturnFormat() == b.getRegistryReturnFormat();
    }

    @SneakyThrows
    private void warmReadyResponse(TranslatedStructureQuery query) {
        adapterRouter.getStructures(query).writeTo(OutputStream.nullOutputStream());
    }

    private void pace(long runGeneration) {
        if (upstreamCalled) {
            long waitNanos = lastUpstreamCallNanos + properties.getUpstreamInterval().toNanos() - nanoTime.getAsLong();
            if (waitNanos > 0 && !sleeper.sleep(TimeUnit.NANOSECONDS.toMillis(waitNanos))) {
                throw new PreheatCancelledException("preheat thread interrupted");
            }
        }
        checkActive(runGeneration);
        lastUpstreamCallNanos = nanoTime.getAsLong();
        upstreamCalled = true;
    }

    private void checkActive(long runGeneration) {
        if (Thread.currentThread().isInterrupted()) {
            throw new PreheatCancelledException("preheat thread interrupted");
        }
        if (generation.get() != runGeneration) {
            throw new PreheatCancelledException("superseded by a configuration change");
        }
    }

    private void record(RunState state, String registry, String result) {
        switch (result) {
            case WARMED -> state.warmed++;
            case SKIPPED -> state.skipped++;
            default -> state.failed++;
        }
        dataflowsCounter.add(1, Attributes.of(REGISTRY, registry, RESULT, result));
    }

    private static DataflowBean single(Set<DataflowBean> dataflows, DataflowReference ref) {
        if (dataflows.size() != 1) {
            throw new PreheatDataflowException("expected one dataflow for " + ref + ", the registry returned " + dataflows.size());
        }
        return dataflows.iterator().next();
    }

    private static <T> Collection<T> nullToEmpty(Collection<T> values) {
        return values == null ? List.of() : values;
    }

    private static Thread newPreheatThread(Runnable task) {
        Thread thread = new Thread(task, "preheat");
        thread.setDaemon(true);
        return thread;
    }

    private record Outcome(String registry, String result) {
    }

    /**
     * Counters of one run. Only the preheat thread touches it.
     */
    private static final class RunState {
        private int warmed;
        private int skipped;
        private int failed;
        private int splitEntries;
        private int upstreamCalls;
        private int refreshes;
        private int refreshesDeferred;
    }
}
