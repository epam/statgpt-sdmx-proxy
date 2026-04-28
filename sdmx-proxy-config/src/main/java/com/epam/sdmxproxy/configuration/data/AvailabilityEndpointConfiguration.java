package com.epam.sdmxproxy.configuration.data;

import java.util.List;

import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(callSuper = true)
public class AvailabilityEndpointConfiguration extends EndpointConfiguration {

    private boolean availabilityEnabled;

    private boolean unwrapStarComponentId;

    /**
     * When true, availability filter parameters are sent as raw dimension query params
     * (e.g., FREQ=Q) instead of the SDMX 3.0 c[] wrapper (e.g., c[FREQ]=Q).
     * Required for registries that do not support the c[] format on availability queries.
     */
    private boolean unwrapFilterParameters;

    /**
     * When true, a key whose every dimension position is '*' is collapsed to a single '*'.
     * Required for registries (e.g., BIS) that accept only one '*' as the match-all form
     * on availability queries.
     * For example, "*.*.*.*" becomes "*".
     */
    private boolean mergeAllWildcardKey;

    /**
     * List of fixtures to apply to the raw response from the registry before conversion/bypass.
     * Each fixture patches a specific known issue in the registry's response (e.g., invalid attributeRelationship).
     * Applied as a chain of responsibility in the order they are listed.
     */
    private List<FixtureConfiguration<AvailabilityFixtureType>> fixtures;

    /**
     * When true, every dim filter is moved into {@code c[]} and the path key is sent as a
     * single {@code *} on outbound requests to this registry's availability endpoint.
     * Workaround for registries (BIS / FusionRegistry) that mishandle the combination of
     * a partially narrowed path key and an enum-dim {@code c[]} filter -- see design 016.
     */
    private boolean convertKeyToFilters;
}
