package com.epam.sdmxproxy.services.sdmxsource;

import com.epam.sdmxproxy.exception.DataConversionException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import io.sdmx.api.io.ReadableDataLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Memory-only replacement for sdmx-core's leaky, disk-spilling
 * {@code SdmxSourceReadableDataLocationFactory}. See design 037 / issue #105.
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryReadableDataLocationFactory {

    private static final int CHUNK_SIZE = 8192;

    private final long maxInMemoryBytes;

    public ReadableDataLocation getReadableDataLocation(InputStream inputStream) {
        return new InMemoryReadableDataLocation(readFully(inputStream));
    }

    private byte[] readFully(InputStream inputStream) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_SIZE];
        long total = 0;
        try (InputStream in = inputStream) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > maxInMemoryBytes) {
                    throw new ResponseTooLargeException("Upstream response exceeded the in-memory processing limit of " + maxInMemoryBytes + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new DataConversionException("Failed to read upstream response into memory", e);
        }
        return buffer.toByteArray();
    }
}
