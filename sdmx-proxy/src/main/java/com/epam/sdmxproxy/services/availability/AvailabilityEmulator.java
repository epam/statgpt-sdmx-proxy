package com.epam.sdmxproxy.services.availability;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

/**
 * Answers an availability request for a registry whose availability endpoint does not work --
 * see design 040.
 * <p>
 * Returns the rendered response in the media type the client asked for. Implementations must
 * either produce a faithful cube region or throw: an empty or partial one raises in the consumer
 * at dataset load, and at query time reads as "nothing is available", which silently drops the
 * dataset from the candidate set.
 */
public interface AvailabilityEmulator {

    /**
     * The configured {@code emulation.type} this implementation serves.
     */
    AvailabilityEmulationType supportedType();

    /**
     * Renders the emulated availability response.
     *
     * @param query    the client's availability request, key and filters intact
     * @param beans    parsed dataflow structures, already fetched and cached by the caller
     * @param context  the upstream calls this emulator may make
     * @return rendered response bytes in {@code query.getContentType()}
     */
    byte[] emulate(TranslatedAvailabilityQuery query, SdmxBeans beans, AvailabilityEmulationContext context);
}
