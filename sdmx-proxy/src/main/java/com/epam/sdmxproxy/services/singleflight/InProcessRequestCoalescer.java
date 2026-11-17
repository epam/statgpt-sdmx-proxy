package com.epam.sdmxproxy.services.singleflight;

import com.epam.sdmxproxy.exception.RegistryResponseTimeoutException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * In-process {@link RequestCoalescer}: one {@link CompletableFuture} per in-flight key.
 * <p>
 * The loader runs on this bean's own executor, never on a caller's thread. Spring interrupts an
 * async request on {@code spring.mvc.async.request-timeout}, and {@code RateLimitRetryClient}
 * aborts its 429 backoff when interrupted, so a loader on the first caller's thread would die
 * with that caller and take everyone else's result with it. Each caller waits with its own
 * bounded {@code get}; the shared future is never cancelled or timed out. See design 042.
 */
@Slf4j
@Service
public class InProcessRequestCoalescer implements RequestCoalescer {

    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration waitTimeout;
    private final LongCounter coalescedCounter;
    private final LongCounter waitTimeoutCounter;

    public InProcessRequestCoalescer(CacheProperties cacheProperties, ObjectProvider<OpenTelemetry> openTelemetry) {
        this.waitTimeout = cacheProperties.getSingleFlight().getWaitTimeout();
        Meter meter = openTelemetry.getIfAvailable(OpenTelemetry::noop).getMeter("sdmx-proxy.single-flight");
        meter.gaugeBuilder("sdmxproxy.singleflight.inflight").ofLongs().setDescription("Upstream calls currently in flight").buildWithCallback(m -> m.record(inFlight.size()));
        this.coalescedCounter = meter.counterBuilder("sdmxproxy.singleflight.coalesced").setDescription("Calls served by joining an in-flight upstream call").build();
        this.waitTimeoutCounter = meter.counterBuilder("sdmxproxy.singleflight.wait_timeouts").setDescription("Callers that gave up waiting for a shared upstream call").build();
    }

    @Override
    public byte[] coalesce(String key, Supplier<byte[]> loader) {
        CompletableFuture<byte[]> candidate = new CompletableFuture<>();
        // putIfAbsent, not computeIfAbsent: a loader that finishes at once runs the removal below on
        // the same key, which inside a computeIfAbsent mapping function is a recursive update.
        CompletableFuture<byte[]> existing = inFlight.putIfAbsent(key, candidate);
        if (existing == null) {
            start(key, candidate, loader);
            return await(key, candidate);
        }
        coalescedCounter.add(1);
        log.info("Single-flight: joined in-flight call for {}", key);
        return await(key, existing);
    }

    private void start(String key, CompletableFuture<byte[]> flight, Supplier<byte[]> loader) {
        // Registered before the task starts, so removal runs on every outcome. remove(key, value)
        // never evicts a newer flight that reused the key.
        flight.whenComplete((bytes, error) -> inFlight.remove(key, flight));
        Runnable task = Context.current().wrap(() -> {
            try {
                flight.complete(loader.get());
            } catch (Throwable t) {
                flight.completeExceptionally(t);
            }
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            flight.completeExceptionally(e);
        }
    }

    private byte[] await(String key, CompletableFuture<byte[]> flight) {
        try {
            return flight.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            waitTimeoutCounter.add(1);
            log.warn("Single-flight: gave up waiting {} for {}; the upstream call continues", waitTimeout, key);
            throw new RegistryResponseTimeoutException("Timed out after " + waitTimeout + " waiting for registry call " + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnexpectedStateException("Interrupted while waiting for registry call " + key, e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new UnexpectedStateException("Registry call failed with a checked exception", cause);
    }

    /**
     * Number of upstream calls currently in flight -- the value the in-flight gauge reports.
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
