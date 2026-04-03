package com.epam.sdmxproxy.configuration.data;

import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Set;


@Data
@EqualsAndHashCode(callSuper = true)
public class StructureEndpointConfiguration extends EndpointConfiguration {

    private Set<String> supportedStructures;

    /**
     * List of fixtures to apply to the raw response from the registry before conversion/bypass.
     * Each fixture patches a specific known issue in the registry's response (e.g., invalid attributeRelationship).
     * Applied as a chain of responsibility in the order they are listed.
     */
    private List<FixtureConfiguration<StructureFixtureType>> fixtures;

}
