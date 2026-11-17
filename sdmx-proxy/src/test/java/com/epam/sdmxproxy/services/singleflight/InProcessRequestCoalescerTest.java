package com.epam.sdmxproxy.services.singleflight;

import com.epam.sdmxproxy.exception.RegistryResponseTimeoutException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency tests for {@link InProcessRequestCoalescer} -- design 042.
 * <p>
 * Loaders block on a latch that the test releases only after every caller thread is parked in
 * the bounded {@code get}; a parked thread has necessarily passed {@code putIfAbsent}, so the
 * join is deterministic. No test uses a sleep as synchronization.
 */
class InProcessRequestCoalescerTest {

    private static final Duration LONG_WAIT = Duration.ofSeconds(30);
    private static final Duration CONDITION_TIMEOUT = Duration.ofSeconds(10);

    private final List<InProcessRequestCoalescer> coalescers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void inFlightRegistryIsEmpty() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(CONDITION_TIMEOUT.toMillis());
        }
        for (InProcessRequestCoalescer coalescer : coalescers) {
            awaitCondition(() -> coalescer.inFlightCount() == 0);
            assertThat(coalescer.inFlightCount()).isZero();
            coalescer.shutdown();
        }
    }

    @Test
    void coalesce_fiftyConcurrentCallersSameKey_runLoaderOnce() throws Exception {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        byte[] payload = bytes("dsd");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<Object>> outcomes = callConcurrently(50, i -> "key", i -> () -> {
            calls.incrementAndGet();
            awaitLatch(release);
            return payload;
        }, coalescer);
        awaitAllParked();
        release.countDown();

        for (CompletableFuture<Object> outcome : outcomes) {
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isSameAs(payload);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void coalesce_tenKeysFiveCallersEach_runLoaderTenTimes() throws Exception {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<Object>> outcomes = callConcurrently(50, i -> "key-" + (i % 10), i -> () -> {
            calls.incrementAndGet();
            awaitLatch(release);
            return bytes("value-" + (i % 10));
        }, coalescer);
        awaitAllParked();
        release.countDown();

        for (int i = 0; i < outcomes.size(); i++) {
            assertThat((byte[]) outcomes.get(i).get(10, TimeUnit.SECONDS)).isEqualTo(bytes("value-" + (i % 10)));
        }
        assertThat(calls).hasValue(10);
    }

    @Test
    void coalesce_someWaitersInterrupted_othersStillGetResult() throws Exception {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        byte[] payload = bytes("dsd");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<Object>> outcomes = callConcurrently(50, i -> "key", i -> () -> {
            calls.incrementAndGet();
            awaitLatch(release);
            return payload;
        }, coalescer);
        awaitAllParked();
        for (int i = 0; i < 20; i++) {
            threads.get(i).interrupt();
        }
        for (int i = 0; i < 20; i++) {
            Object outcome = outcomes.get(i).get(10, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(InterruptedOutcome.class);
            assertThat(((InterruptedOutcome) outcome).error()).isInstanceOf(UnexpectedStateException.class);
            assertThat(((InterruptedOutcome) outcome).interruptFlagSet()).isTrue();
        }
        release.countDown();

        for (int i = 20; i < 50; i++) {
            assertThat(outcomes.get(i).get(10, TimeUnit.SECONDS)).isSameAs(payload);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void coalesce_allWaitersGone_loaderStillCompletes() throws Exception {
        InProcessRequestCoalescer coalescer = newCoalescer(Duration.ofMillis(50));
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean loaderCompleted = new AtomicBoolean();

        List<CompletableFuture<Object>> outcomes = callConcurrently(5, i -> "key", i -> () -> {
            awaitLatch(release);
            loaderCompleted.set(true);
            return bytes("late");
        }, coalescer);

        for (CompletableFuture<Object> outcome : outcomes) {
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(RegistryResponseTimeoutException.class);
        }
        assertThat(coalescer.inFlightCount()).isEqualTo(1);

        release.countDown();
        awaitCondition(() -> coalescer.inFlightCount() == 0);
        assertThat(loaderCompleted).isTrue();
    }

    @Test
    void coalesce_loaderThrows_allWaitersGetSameException() throws Exception {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        RuntimeException boom = new IllegalStateException("upstream failed");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<Object>> outcomes = callConcurrently(10, i -> "key", i -> () -> {
            calls.incrementAndGet();
            awaitLatch(release);
            throw boom;
        }, coalescer);
        awaitAllParked();
        release.countDown();

        for (CompletableFuture<Object> outcome : outcomes) {
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isSameAs(boom);
        }
        awaitCondition(() -> coalescer.inFlightCount() == 0);

        byte[] retried = coalescer.coalesce("key", () -> {
            calls.incrementAndGet();
            return bytes("recovered");
        });
        assertThat(retried).isEqualTo(bytes("recovered"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void coalesce_checkedExceptionCause_wrappedInUnexpectedState() {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        IOException io = new IOException("socket closed");

        Throwable thrown = catchThrowable(() -> coalescer.coalesce("key", () -> sneakyThrow(io)));

        assertThat(thrown).isInstanceOf(UnexpectedStateException.class).hasCause(io);
    }

    @Test
    void coalesce_afterCompletion_nextCallRunsNewFlight() {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        AtomicInteger calls = new AtomicInteger();

        coalescer.coalesce("key", () -> bytes("v" + calls.incrementAndGet()));
        awaitCondition(() -> coalescer.inFlightCount() == 0);
        byte[] second = coalescer.coalesce("key", () -> bytes("v" + calls.incrementAndGet()));

        assertThat(second).isEqualTo(bytes("v2"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void coalesce_immediateLoader_noRecursiveUpdate() {
        InProcessRequestCoalescer coalescer = newCoalescer(LONG_WAIT);
        byte[] payload = bytes("fast");

        for (int i = 0; i < 10_000; i++) {
            assertThat(coalescer.coalesce("key", () -> payload)).isSameAs(payload);
        }
    }

    // --- helpers ---

    private record InterruptedOutcome(Throwable error, boolean interruptFlagSet) {
    }

    private InProcessRequestCoalescer newCoalescer(Duration waitTimeout) {
        CacheProperties properties = new CacheProperties();
        properties.getSingleFlight().setWaitTimeout(waitTimeout);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenTelemetry> openTelemetry = mock(ObjectProvider.class);
        when(openTelemetry.getIfAvailable(any())).thenReturn(OpenTelemetry.noop());
        InProcessRequestCoalescer coalescer = new InProcessRequestCoalescer(properties, openTelemetry);
        coalescers.add(coalescer);
        return coalescer;
    }

    /**
     * Starts {@code n} platform threads, each calling {@code coalesce} once. Each returned future
     * completes with the bytes, the thrown exception, or an {@link InterruptedOutcome} when the
     * call threw with the thread's interrupt flag set.
     */
    private List<CompletableFuture<Object>> callConcurrently(int n, IntFunction<String> keyFor, IntFunction<java.util.function.Supplier<byte[]>> loaderFor, InProcessRequestCoalescer coalescer) {
        List<CompletableFuture<Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            CompletableFuture<Object> outcome = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    outcome.complete(coalescer.coalesce(keyFor.apply(index), loaderFor.apply(index)));
                } catch (Throwable t) {
                    outcome.complete(Thread.currentThread().isInterrupted() ? new InterruptedOutcome(t, true) : t);
                }
            }, "coalesce-caller-" + i);
            threads.add(thread);
            outcomes.add(outcome);
            thread.start();
        }
        return outcomes;
    }

    private void awaitAllParked() {
        awaitCondition(() -> threads.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING));
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + CONDITION_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + CONDITION_TIMEOUT);
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CONDITION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("Latch not released within " + CONDITION_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
