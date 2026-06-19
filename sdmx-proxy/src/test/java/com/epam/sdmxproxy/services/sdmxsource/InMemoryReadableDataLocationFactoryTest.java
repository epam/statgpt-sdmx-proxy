package com.epam.sdmxproxy.services.sdmxsource;

import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import io.sdmx.api.io.ReadableDataLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InMemoryReadableDataLocationFactory}. See design 037 / issue #105.
 */
class InMemoryReadableDataLocationFactoryTest {

    @Test
    void getReadableDataLocation_smallStream_readsAllBytes() throws Exception {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        InMemoryReadableDataLocationFactory factory = new InMemoryReadableDataLocationFactory(1024);
        ReadableDataLocation location = factory.getReadableDataLocation(new ByteArrayInputStream(content));
        assertArrayEquals(content, location.getInputStream().readAllBytes());
    }

    @Test
    void getReadableDataLocation_overCap_throwsResponseTooLargeException() {
        byte[] content = new byte[1024];
        InMemoryReadableDataLocationFactory factory = new InMemoryReadableDataLocationFactory(16);
        assertThrows(ResponseTooLargeException.class, () -> factory.getReadableDataLocation(new ByteArrayInputStream(content)));
    }

    @Test
    void getReadableDataLocation_closesSourceStream() {
        ClosableByteArrayInputStream source = new ClosableByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        InMemoryReadableDataLocationFactory factory = new InMemoryReadableDataLocationFactory(1024);
        factory.getReadableDataLocation(source);
        assertTrue(source.isClosed());
    }

    @Test
    void getReadableDataLocation_atExactCap_succeeds() throws Exception {
        byte[] content = new byte[16];
        InMemoryReadableDataLocationFactory factory = new InMemoryReadableDataLocationFactory(16);
        ReadableDataLocation location = factory.getReadableDataLocation(new ByteArrayInputStream(content));
        assertArrayEquals(content, location.getInputStream().readAllBytes());
    }

    private static final class ClosableByteArrayInputStream extends ByteArrayInputStream {
        private boolean closed = false;

        ClosableByteArrayInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }
}
