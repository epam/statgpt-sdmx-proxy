package com.epam.sdmxproxy.configuration.data;

import java.util.List;

import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataEndpointConfiguration extends EndpointConfiguration {

    /**
     * When true, empty dimensions in the data query key are replaced with '*'.
     * Required for registries (e.g., IMF) that treat an empty dimension
     * differently from the wildcard '*' in data queries.
     * For example, ".L_T.P_F3" becomes "*.L_T.P_F3".
     */
    private boolean replaceEmptyDimensionsWithWildcard;

    /**
     * When true, a key whose every dimension position is '*' is collapsed to a single '*'.
     * Required for registries (e.g., BIS) that accept only one '*' as the match-all form
     * and return no data for a per-dimension expansion.
     * For example, "*.*.*.*" becomes "*".
     */
    private boolean mergeAllWildcardKey;

    /**
     * List of fixtures to apply to the raw registry data response before conversion.
     * Each fixture patches a specific known issue (e.g., non-canonical TIME_PERIOD values).
     * Applied as a chain of responsibility in the order they are listed.
     * Data fixtures must be streaming -- see {@code DataFixture}.
     */
    private List<FixtureConfiguration<DataFixtureType>> fixtures;

    /**
     * When true, the registry natively supports the SDMX 3.0 {@code limit} query parameter on
     * the data endpoint and the proxy passes it through. When false, the proxy emulates
     * {@code limit} via availability probing and streaming truncation -- see design 014.
     * <p>
     * Default is true: existing registries retain passthrough behavior. Set to false only for
     * registries that ignore the parameter (e.g. BIS, which does not accept it).
     */
    private boolean supportsLimit = true;

    /**
     * Overshoot factor used when emulating {@code limit}. The shrink algorithm aims to land
     * the registry-side series count in the band {@code [limit, floor(limit *
     * limitEmulationTolerance)]}. A higher value means fewer availability probes but a
     * larger data response to truncate; a lower value means tighter shrinks and more probes.
     * Has no effect when {@link #supportsLimit} is true.
     */
    private double limitEmulationTolerance = 1.2d;

    /**
     * Hard cap on the total number of availability probes the limit-emulation algorithm may
     * issue per request. Bounds the worst-case latency (each probe is one HTTP round-trip).
     * Has no effect when {@link #supportsLimit} is true.
     */
    private int limitEmulationProbeBudget = 8;

    /**
     * When true, every dim filter is moved into {@code c[]} and the path key is sent as a
     * single {@code *} on outbound requests to this registry's data endpoint. Workaround
     * for registries (BIS / FusionRegistry) that mishandle the combination of a partially
     * narrowed path key and an enum-dim {@code c[]} filter -- see design 016.
     */
    private boolean convertKeyToFilters;
}
