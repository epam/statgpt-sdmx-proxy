package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.io.ReadableDataLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link InMemoryReadableDataLocation}. Exercises the re-readable
 * contract and the inherited {@link io.sdmx.utils.core.io.AbstractReadableDataLocation}
 * close guard directly. See design 037 / issue #105.
 */
class InMemoryReadableDataLocationTest {

    private static final byte[] CONTENT = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

    @Test
    void getInputStream_returnsFreshStreamEachCall() throws Exception {
        InMemoryReadableDataLocation location = new InMemoryReadableDataLocation(CONTENT);
        InputStream first = location.getInputStream();
        InputStream second = location.getInputStream();
        assertNotSame(first, second);
        assertArrayEquals(CONTENT, first.readAllBytes());
        assertArrayEquals(CONTENT, second.readAllBytes());
    }

    @Test
    void getInputStream_afterClose_throws() {
        InMemoryReadableDataLocation location = new InMemoryReadableDataLocation(CONTENT);
        location.close();
        assertThrows(RuntimeException.class, location::getInputStream);
    }

    @Test
    void getSize_returnsByteLength() {
        InMemoryReadableDataLocation location = new InMemoryReadableDataLocation(CONTENT);
        assertEquals(CONTENT.length, location.getSize());
    }

    @Test
    void copy_readsSameContent() throws Exception {
        InMemoryReadableDataLocation location = new InMemoryReadableDataLocation(CONTENT, "name");
        ReadableDataLocation copy = location.copy();
        assertEquals("name", copy.getName());
        assertArrayEquals(CONTENT, copy.getInputStream().readAllBytes());
    }
}
