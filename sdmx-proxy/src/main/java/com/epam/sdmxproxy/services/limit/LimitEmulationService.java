package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

/**
 * Runs the heuristic shrink loop (see design 014) and returns a modified
 * {@link TranslatedDataQuery} whose key / filters have been narrowed so that the
 * registry's response contains at most {@code N * tolerance} series. The returned query
 * has {@code .limit(null)} -- callers must truncate the registry's response to exactly
 * {@code N} series themselves (via
 * {@link com.epam.sdmxproxy.services.limit.truncate.SeriesLimitTruncator}).
 * <p>
 * Availability probes are issued via an {@link AvailabilityProber} callback supplied by
 * the caller (normally {@code AdapterRouter}) -- this service never talks to
 * {@code GenericRegistryAdapter} directly.
 */
public interface LimitEmulationService {

    TranslatedDataQuery getShrunkQuery(
            TranslatedDataQuery query,
            SdmxBeans sdmxBeans,
            AvailabilityProber prober
    );
}
