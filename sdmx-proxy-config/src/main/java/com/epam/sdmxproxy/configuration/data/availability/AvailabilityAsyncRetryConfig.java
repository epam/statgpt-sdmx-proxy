package com.epam.sdmxproxy.configuration.data.availability;

import lombok.Data;

/**
 * Retry schedule for a data probe that came back queued rather than answered -- see design 040.
 * All durations are in milliseconds.
 * <p>
 * Eurostat may answer a data request with HTTP 200 whose body is a SOAP {@code <queued>} envelope
 * instead of data, carrying the {@code Content-Type} of the requested format. Re-issuing the
 * identical URL once the extraction job finishes is what returns the real payload, so the fix is
 * a bounded retry of the same request rather than driving the asynchronous extraction API.
 * <p>
 * Kept separate from {@code RegistryRateLimitRetryConfig}: this is not a rate-limit backoff, it
 * waits on an upstream job to finish and applies only to the emulation probe path.
 */
@Data
public class AvailabilityAsyncRetryConfig {

    /**
     * Retry a queued probe. When false, a queued response fails the request immediately with
     * HTTP 503 rather than waiting.
     */
    private boolean enabled = true;

    /**
     * Maximum attempts including the initial probe.
     */
    private int maxAttempts = 4;

    /**
     * Initial interval for exponential backoff.
     */
    private long initialIntervalMillis = 2_000L;

    /**
     * Multiplier for exponential backoff.
     */
    private double multiplier = 2.0d;

    /**
     * Ceiling for the summed wait across one queued cycle. Bounds the worst case when the
     * upstream job never completes.
     */
    private long maxTotalWaitMillis = 30_000L;
}
