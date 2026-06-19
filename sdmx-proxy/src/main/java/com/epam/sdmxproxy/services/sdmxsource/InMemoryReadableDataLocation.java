package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.utils.core.io.AbstractReadableDataLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * A {@link ReadableDataLocation} backed entirely by an in-memory byte array.
 * <p>
 * Replaces sdmx-core's {@code SdmxSourceReadableDataLocationFactory}, whose shared
 * memory-budget counter leaked (never decremented because we never closed the
 * locations) and forced every request onto a fragile temp-file path that hangs in
 * our container. See design 037 / issue #105.
 * <p>
 * Each {@link #getInputStream()} returns a fresh stream over the same array, so the
 * re-readable contract (format sniffing, then parse) holds. Holding the whole
 * payload in heap is acceptable because the upstream read is size-capped upstream
 * by {@link InMemoryReadableDataLocationFactory}.
 */
public class InMemoryReadableDataLocation extends AbstractReadableDataLocation {

    private static final long serialVersionUID = 1L;

    private final byte[] bytes;
    private final String name;

    public InMemoryReadableDataLocation(byte[] bytes) {
        this(bytes, null);
    }

    public InMemoryReadableDataLocation(byte[] bytes, String name) {
        this.bytes = bytes;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    protected InputStream getStreamInternal() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    protected void closeInternal() {
        // Nothing to release: the byte[] is GC-managed and no OS handles are held.
        // Re-reads after close are already blocked by AbstractReadableDataLocation.
    }

    @Override
    protected long calculateSize() {
        return bytes.length;
    }

    @Override
    public ReadableDataLocation copy() {
        // The array is never mutated, so sharing it across copies is safe.
        return new InMemoryReadableDataLocation(bytes, name);
    }
}
