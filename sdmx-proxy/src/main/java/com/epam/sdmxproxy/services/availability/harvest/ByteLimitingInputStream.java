package com.epam.sdmxproxy.services.availability.harvest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.epam.sdmxproxy.exception.ResponseTooLargeException;

/**
 * Fails the read once more than {@code maxBytes} have been consumed -- see design 040.
 * <p>
 * The emulation probe is the one upstream response the proxy reads without buffering, so the
 * in-memory ceiling enforced by {@code InMemoryReadableDataLocationFactory} never applies to it.
 * This is that ceiling for the streaming path.
 */
public class ByteLimitingInputStream extends FilterInputStream {

    private final long maxBytes;
    private final String description;
    private long consumed;

    public ByteLimitingInputStream(InputStream delegate, long maxBytes, String description) {
        super(delegate);
        this.maxBytes = maxBytes;
        this.description = description;
    }

    public long consumed() {
        return consumed;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    private void count(int n) {
        consumed += n;
        if (consumed > maxBytes) {
            throw new ResponseTooLargeException(
                    "Availability emulation probe for " + description + " exceeded the "
                            + maxBytes + " byte limit; narrow the request or raise "
                            + "availabilityEndpointConfig.emulation.maxProbeBytes");
        }
    }
}
