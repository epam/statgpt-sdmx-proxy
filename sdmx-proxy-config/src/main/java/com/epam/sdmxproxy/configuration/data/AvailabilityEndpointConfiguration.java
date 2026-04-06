package com.epam.sdmxproxy.configuration.data;

import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;


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
     * List of fixtures to apply to the raw response from the registry before conversion/bypass.
     * Each fixture patches a specific known issue in the registry's response (e.g., invalid attributeRelationship).
     * Applied as a chain of responsibility in the order they are listed.
     */
    private List<FixtureConfiguration<AvailabilityFixtureType>> fixtures;
}
