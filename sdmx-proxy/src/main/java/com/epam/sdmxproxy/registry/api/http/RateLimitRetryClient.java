package com.epam.sdmxproxy.registry.api.http;

import feign.Client;
import feign.Request;
import feign.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Feign {@link Client} decorator that retries HTTP 429 (Too Many Requests) with exponential backoff.
 * <p>
 * It sits below Feign's decoders and inside the resilience4j circuit breaker, which matters twice
 * over: it sees the raw {@link Response} so {@code Retry-After} is available without unwrapping a
 * {@code FeignException}, and a call that is retried into a success never reaches the breaker as a
 * failure at all.
 * <p>
 * Retrying is safe because every SDMX client method is a bodyless {@code GET}, so replaying the
 * {@link Request} is idempotent and needs no buffering.
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitRetryClient implements Client {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String RETRY_AFTER_HEADER = "retry-after";

    private final Client delegate;
    private final RateLimitRetrySettings settings;
    private final Sleeper sleeper;

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Response response = delegate.execute(request, options);
        long totalWaited = 0L;
        for (int attempt = 1; attempt < settings.maxAttempts() && response.status() == TOO_MANY_REQUESTS; attempt++) {
            long waitMillis = resolveWaitMillis(response, attempt);
            if (totalWaited + waitMillis > settings.maxTotalWaitMillis()) {
                log.warn("Registry returned 429 for {}; total wait budget of {} ms exhausted, giving up", request.url(), settings.maxTotalWaitMillis());
                break;
            }
            log.warn("Registry returned 429 for {}; retry {}/{} in {} ms", request.url(), attempt, settings.maxAttempts() - 1, waitMillis);
            closeQuietly(response);
            if (!sleeper.sleep(waitMillis)) {
                log.warn("Interrupted while backing off from 429 for {}; aborting retries", request.url());
                return delegate.execute(request, options);
            }
            totalWaited += waitMillis;
            response = delegate.execute(request, options);
        }
        return response;
    }

    /**
     * Exponential backoff, raised to the registry's {@code Retry-After} when that asks for longer,
     * then clamped to {@code maxIntervalMillis}. The clamp is not optional: without it a registry
     * answering {@code Retry-After: 3600} would block the calling thread for an hour per attempt.
     */
    private long resolveWaitMillis(Response response, int attempt) {
        long backoff = exponentialBackoffMillis(attempt);
        long retryAfter = parseRetryAfterMillis(response);
        return Math.min(Math.max(backoff, retryAfter), settings.maxIntervalMillis());
    }

    private long exponentialBackoffMillis(int attempt) {
        double raw = settings.initialIntervalMillis() * Math.pow(settings.multiplier(), attempt - 1.0);
        if (raw >= settings.maxIntervalMillis()) {
            return settings.maxIntervalMillis();
        }
        return (long) raw;
    }

    /**
     * Reads {@code Retry-After} in its delta-seconds form. Returns 0 when the header is absent,
     * unparseable, zero or negative -- which is what neutralises registries fronted by Cloudflare,
     * observed returning a literal {@code Retry-After: 0} alongside a 429.
     * <p>
     * The HTTP-date form of the header is not supported and is treated as 0, falling back to
     * exponential backoff.
     */
    private long parseRetryAfterMillis(Response response) {
        Map<String, Collection<String>> headers = response.headers();
        if (headers == null) {
            return 0L;
        }
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            if (!RETRY_AFTER_HEADER.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            Collection<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                return 0L;
            }
            return parseDeltaSeconds(values.iterator().next());
        }
        return 0L;
    }

    private long parseDeltaSeconds(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 0L ? seconds * 1000L : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Closes a 429 response that is about to be discarded. The Feign builder sets
     * {@code doNotCloseAfterDecode()}, so nothing else will close it and leaking it would exhaust
     * the OkHttp connection pool. The final returned response is deliberately left open for
     * {@code InputStreamFeignDecoder}.
     */
    private void closeQuietly(Response response) {
        try {
            response.close();
        } catch (Exception e) {
            log.debug("Failed to close discarded 429 response", e);
        }
    }
}
