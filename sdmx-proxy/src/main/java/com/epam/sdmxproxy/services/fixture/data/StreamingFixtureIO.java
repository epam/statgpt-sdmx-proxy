package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared plumbing for streaming data fixtures.
 * <p>
 * Given a source {@link InputStream} and a transform that reads from it and writes to an
 * {@link OutputStream}, wires up a {@link PipedInputStream} / {@link PipedOutputStream} pair
 * driven by a virtual thread and returns an {@link InputStream} that produces transformed
 * bytes on demand. Producer-thread failures are surfaced to the consumer as {@link IOException}.
 * <p>
 * The transform runs on a virtual thread; the consumer reads from a returned stream that
 * blocks on the pipe buffer. This gives natural backpressure without materializing the full
 * response in memory.
 * <p>
 * There is no shared mutable state across concurrent calls other than a lock-free
 * {@link AtomicLong} used to give every producer thread a unique name for log observability;
 * this class is safe for concurrent use.
 */
@Slf4j
@Component
public class StreamingFixtureIO {

    static final int DEFAULT_PIPE_BUFFER_BYTES = 64 * 1024;

    private final AtomicLong threadCounter = new AtomicLong();

    @FunctionalInterface
    public interface StreamingTransform {
        void apply(InputStream in, OutputStream out) throws Exception;
    }

    public InputStream runOnVirtualThread(InputStream input, String threadNamePrefix, StreamingTransform transform) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch producerDone = new CountDownLatch(1);

        PipedOutputStream pos;
        PipedInputStream pis;
        try {
            pos = new PipedOutputStream();
            pis = new PipedInputStream(pos, DEFAULT_PIPE_BUFFER_BYTES);
        } catch (IOException e) {
            throw new UnexpectedStateException("Failed to set up piped streams for data fixture", e);
        }

        String threadName = threadNamePrefix + "-" + threadCounter.getAndIncrement();
        Thread.ofVirtual().name(threadName).start(() -> {
            try (InputStream in = input; OutputStream out = pos) {
                transform.apply(in, out);
            } catch (Throwable t) {
                failure.set(t);
                log.error("Data fixture {} failed", threadName, t);
            } finally {
                producerDone.countDown();
            }
        });

        return new FailurePropagatingInputStream(pis, failure, producerDone);
    }

    /**
     * Wraps the consumer-side pipe so that a producer-thread failure surfaces as
     * {@link IOException} on {@code read()}. On EOF the consumer waits on
     * {@code producerDone} to avoid races where the producer has not yet recorded its failure.
     */
    private static final class FailurePropagatingInputStream extends FilterInputStream {
        private final AtomicReference<Throwable> failure;
        private final CountDownLatch producerDone;

        private FailurePropagatingInputStream(
                InputStream delegate,
                AtomicReference<Throwable> failure,
                CountDownLatch producerDone
        ) {
            super(delegate);
            this.failure = failure;
            this.producerDone = producerDone;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                if (b < 0) {
                    awaitProducer();
                }
                checkFailure();
                return b;
            } catch (IOException e) {
                checkFailure();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = super.read(b, off, len);
                if (n < 0) {
                    awaitProducer();
                }
                checkFailure();
                return n;
            } catch (IOException e) {
                checkFailure();
                throw e;
            }
        }

        private void awaitProducer() throws IOException {
            try {
                producerDone.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for data fixture producer", ie);
            }
        }

        private void checkFailure() throws IOException {
            Throwable t = failure.get();
            if (t != null) {
                throw new IOException("Data fixture producer failed", t);
            }
        }
    }
}
