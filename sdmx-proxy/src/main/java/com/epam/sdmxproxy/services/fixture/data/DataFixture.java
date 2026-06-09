package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Base interface for data-endpoint fixtures.
 * <p>
 * Implementations MUST process the input as a stream and return an {@link InputStream} that
 * produces transformed bytes on demand. Buffering the entire response (e.g. via
 * {@code ObjectMapper.readTree}, {@code InputStream.readAllBytes}, Jackson's
 * {@code TokenBuffer}, or equivalent) is forbidden: data responses may be arbitrarily large
 * and are returned to clients as a streaming body. Peak additional memory per request should
 * be bounded by a small, fixed buffer independent of response size.
 */
public interface DataFixture {

    /** Identifier used to match against {@code FixtureConfiguration.getType()}. */
    DataFixtureType getType();

    /** Registry return formats this implementation can transform. */
    Set<SdmxFormat> supportedFormats();

    /**
     * Wraps {@code input} with this fixture's streaming transformation.
     *
     * @param input     raw registry response stream
     * @param sdmxBeans DSD context for fixtures that need component metadata; may be
     *                  {@code null} if the caller could not resolve it
     * @param config    free-form key/value configuration for this fixture instance
     * @return an {@link InputStream} that yields the transformed bytes
     */
    InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config);
}
