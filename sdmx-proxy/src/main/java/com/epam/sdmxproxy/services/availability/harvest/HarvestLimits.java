package com.epam.sdmxproxy.services.availability.harvest;

import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;

/**
 * Ceilings a harvester enforces while reading a probe -- see design 040.
 * <p>
 * These exist because the probe path is the one place the proxy reads an unbounded upstream
 * response without buffering it, so the usual guard
 * ({@code InMemoryReadableDataLocationFactory}'s in-memory cap) never sees it.
 *
 * @param maxBytes  byte ceiling on the probe response
 * @param maxSeries row ceiling on the probe response
 */
public record HarvestLimits(long maxBytes, long maxSeries) {

    public static HarvestLimits from(AvailabilityEmulationConfiguration config) {
        return new HarvestLimits(config.getMaxProbeBytes(), config.getMaxProbeSeries());
    }

    public static HarvestLimits unlimited() {
        return new HarvestLimits(Long.MAX_VALUE, Long.MAX_VALUE);
    }
}
