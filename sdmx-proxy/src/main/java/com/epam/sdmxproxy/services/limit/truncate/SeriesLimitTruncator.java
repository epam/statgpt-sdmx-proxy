package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.io.InputStream;
import java.util.Set;

/**
 * Cuts a raw registry data response at {@code N} distinct series, streaming. One
 * implementation per registry data format (SDMX-JSON 1.0.0, SDMX-JSON 2.0.0, SDMX-CSV
 * 2.0.0, ...). Each implementation registers its supported {@link SdmxFormat}s; lookup
 * is via {@link SeriesLimitTruncatorProvider}.
 */
public interface SeriesLimitTruncator {

    Set<SdmxFormat> supportedFormats();

    /**
     * Wraps the raw registry stream; the returned stream emits at most {@code n} distinct
     * series worth of bytes. Implementations MUST stream -- do not buffer the full input.
     * {@code sdmxBeans} is supplied for DSD-driven truncators (CSV uses it to locate
     * dimension columns); format-agnostic implementations may ignore it.
     */
    InputStream truncate(InputStream rawData, int n, SdmxBeans sdmxBeans);

    /**
     * Produces a well-formed empty payload in this format -- zero series, but valid input
     * to {@code StreamingDataConversionService.convert}. Used by the {@code N <= 0}
     * short-circuit in {@link com.epam.sdmxproxy.services.limit.LimitEmulationService}.
     * {@code sdmxBeans} is required by DSD-driven formats (CSV header row); JSON
     * implementations may ignore it.
     */
    InputStream emptyStream(SdmxBeans sdmxBeans);
}
