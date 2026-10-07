package com.epam.sdmxproxy.services.availability.harvest;

import java.io.InputStream;
import java.util.Set;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

/**
 * Reduces a series-key data response to per-dimension value sets -- see design 040.
 * <p>
 * Implementations MUST stream and MUST NOT materialize the key set: a probe response runs to
 * hundreds of megabytes while the coverage it yields is a few hundred strings, and that ratio is
 * the whole reason emulation is affordable.
 * <p>
 * Shaped after {@code SeriesLimitTruncator}: one implementation per format family, resolved by
 * {@link SeriesKeyHarvesterProvider}.
 */
public interface SeriesKeyHarvester {

    /**
     * Formats this harvester can read. Must not overlap with another registered harvester.
     */
    Set<SdmxFormat> supportedFormats();

    /**
     * Streams {@code probeResponse} and returns the coverage it carries. The caller owns closing
     * the stream.
     *
     * @param probeResponse series-key data response
     * @param sdmxBeans     dataflow structures, for formats whose series keys are positional
     *                      rather than named
     * @param limits        byte and row ceilings to enforce while reading
     */
    HarvestedCoverage harvest(InputStream probeResponse, SdmxBeans sdmxBeans, HarvestLimits limits);
}
