package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.FixtureType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Base interface for all fixtures.
 * A fixture patches a known issue in a registry's raw response before it is processed.
 */
public interface Fixture {

    /**
     * Returns the type of this fixture, used to match against {@link com.epam.sdmxproxy.configuration.data.FixtureConfiguration#getType()}.
     */
    FixtureType getType();

    /**
     * Returns the set of return formats this fixture can handle.
     */
    Set<ReturnFormat> supportedFormats();

    /**
     * Applies the fixture to the given input stream.
     *
     * @param input  raw response stream from the registry
     * @param config fixture-specific configuration parameters
     * @return a new input stream with the fix applied
     */
    InputStream apply(InputStream input, Map<String, String> config);
}
