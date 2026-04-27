package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;

import java.io.InputStream;

/**
 * Parses the raw SDMX availability response into an {@link AvailabilityProjection} --
 * a dimensionId -> list-of-values projection used by the limit-emulation shrink loop.
 */
public interface AvailabilityResponseParser {

    /**
     * Indicates whether this parser handles the given registry format.
     */
    boolean supports(ReturnFormat format);

    /**
     * Streams through {@code availabilityResponseStream} and returns the projection. Must
     * not buffer the full response. Caller owns stream close semantics.
     */
    AvailabilityProjection parse(InputStream availabilityResponseStream, ReturnFormat format);
}
