package com.epam.sdmxproxy.configuration.data.availability;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import lombok.Data;

/**
 * Per-registry configuration for availability emulation -- see design 040.
 * <p>
 * Only consulted when {@code availabilityEndpointConfig.availabilityEnabled} is false. When
 * availability works natively, none of this applies and the proxy behaves exactly as before.
 */
@Data
public class AvailabilityEmulationConfiguration {

    /**
     * Emulation strategy. Default {@link AvailabilityEmulationType#NONE} means availability
     * requests are rejected with HTTP 501 rather than emulated, so enabling emulation is always
     * an explicit configuration decision.
     */
    private AvailabilityEmulationType type = AvailabilityEmulationType.NONE;

    /**
     * Source for a request that carries no narrowing at all. Has no effect on narrowed requests,
     * which are always probed.
     */
    private UnfilteredAvailabilitySource unfilteredSource = UnfilteredAvailabilitySource.CONTENT_CONSTRAINT;

    /**
     * Structure type to fetch the {@code Actual} constraint from. When null, resolved from the
     * registry's SDMX version: {@code contentconstraint} on SDMX 2.1, {@code dataconstraint} on
     * SDMX 3.0. Set explicitly only for a registry that names the resource differently.
     */
    private String constraintStructureType;

    /**
     * Format to request for the probe. When null, the cheapest CSV format present in
     * {@code dataEndpointConfig.supportedFormats} is used, falling back to that endpoint's
     * {@code defaultFormat}.
     * <p>
     * CSV is preferred because the registry has already done the deduplication: dropping the
     * observations drops the {@code TIME_PERIOD} column, so every row is a distinct series key.
     * Measured 3.36x smaller than SDMX-ML for the same key set.
     */
    private SdmxFormat probeFormat;

    /**
     * Value of the SDMX 2.1 {@code detail} parameter on the probe. {@code serieskeysonly} returns
     * the series keys with neither attributes nor observations.
     * <p>
     * SDMX 2.1 only -- {@code detail} does not exist in SDMX-REST 2.x, where the probe sends
     * {@code attributes=none&measures=none} instead. Rejected at config load on an
     * {@code SDMX_3_0} version.
     */
    private String probeDetail = "serieskeysonly";

    /**
     * Emit a {@code TIME_PERIOD} key value in the emulated response.
     * <p>
     * Default false, for four reasons: the consumer's JSON schema has no representation for a
     * time range, so bounds cannot reach it at all; an enumerated list is payload with no
     * consumer; the registries that serve availability natively (IMF, BIS) emit no
     * {@code TIME_PERIOD} key value either; and a probe cannot produce time coverage, so
     * enabling this would make the unfiltered and narrowed responses structurally different.
     */
    private boolean includeTimePeriod = false;

    /**
     * Byte ceiling on a single probe response. Exceeding it aborts the request rather than
     * reading on. The measured worst case on Eurostat is 139 MB for a whole-cube probe.
     */
    private long maxProbeBytes = 268_435_456L;

    /**
     * Row ceiling on a single probe response. One row is one distinct series key.
     */
    private long maxProbeSeries = 5_000_000L;

    /**
     * Read timeout for the probe, separate from the registry's general {@code readTimeout}: a
     * broad probe legitimately runs far longer than an ordinary data request should.
     */
    private int probeTimeoutMillis = 180_000;

    /**
     * Probe size above which the emulator splits the request by key instead of reading one large
     * response. Bytes transferred are unchanged; each request becomes short, which keeps the
     * circuit breaker's latency view sane and avoids a single minute-long upstream call.
     */
    private long probeSplitThresholdBytes = 33_554_432L;

    /**
     * Number of values of the split dimension pinned per chunk when decomposing.
     */
    private int probeSplitChunkSize = 8;

    /**
     * Hard cap on the number of probes issued for one availability request, including the first.
     * Bounds worst-case latency and upstream load. Exhausting it fails the request.
     */
    private int maxProbeFanOut = 16;

    /**
     * Retry schedule for a probe that came back queued rather than answered.
     */
    private AvailabilityAsyncRetryConfig asyncRetry = new AvailabilityAsyncRetryConfig();
}
