package com.epam.sdmxproxy.configuration.data;

import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;
import java.util.Set;


@Data
@EqualsAndHashCode(callSuper = true)
public class StructureEndpointConfiguration extends EndpointConfiguration {

    private Set<String> supportedStructures;

    /**
     * The {@code references} values this registry accepts when the query names one concrete
     * artefact. A requested value outside the set is rewritten through
     * {@link #referencesDowngrade}, or rejected when the map has no entry for it.
     * <p>
     * Null or empty disables the check, so registries without the field keep forwarding whatever
     * the client sent.
     */
    private Set<String> supportedReferences;

    /**
     * The {@code references} values this registry accepts when the query is a wildcard over all
     * artefacts ({@code *} or {@code all} in the resource id slot). Eurostat accepts
     * {@code descendants} for one dataflow and rejects it for all dataflows with
     * {@code ERR_ALL_FLOWS_REFERENCES}; its 3.0 endpoint answers 200 and drops the references
     * silently, which is worse.
     * <p>
     * Null falls back to {@link #supportedReferences}.
     */
    private Set<String> supportedReferencesForAll;

    /**
     * What to send instead when the requested {@code references} value is outside the accepted
     * set for the query's scope. Keys and values are {@code references} values; a value of
     * {@code "none"} drops the references entirely.
     * <p>
     * A value absent from this map is a rejection: the proxy answers 400 naming the accepted set
     * rather than letting the registry return an opaque fault.
     */
    private Map<String, String> referencesDowngrade;

    /**
     * List of fixtures to apply to the raw response from the registry before conversion/bypass.
     * Each fixture patches a specific known issue in the registry's response (e.g., invalid attributeRelationship).
     * Applied as a chain of responsibility in the order they are listed.
     */
    private List<FixtureConfiguration<StructureFixtureType>> fixtures;

}
