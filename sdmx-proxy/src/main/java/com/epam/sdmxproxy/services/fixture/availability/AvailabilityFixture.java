package com.epam.sdmxproxy.services.fixture.availability;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Base interface for all fixtures.
 * A fixture patches a known issue in a registry's raw response before it is processed.
 */
public interface AvailabilityFixture {

    /**
     * Returns the type of this fixture, used to match against {@link FixtureConfiguration#getType()}.
     */
    AvailabilityFixtureType getType();

    /**
     * Returns the set of return formats this fixture can handle.
     */
    Set<ReturnFormat> supportedFormats();


    InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config);
}
