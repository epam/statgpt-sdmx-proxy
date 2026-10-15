package com.epam.sdmxproxy.configuration.data.availability;

/**
 * Where the emulator gets the answer for an availability request that carries <em>no</em>
 * narrowing at all -- see design 040.
 * <p>
 * This choice applies only to the unfiltered request. A narrowed request is always answered from
 * a data probe, never from the content constraint: the constraint is dataflow-scoped and silently
 * ignores narrowing, so serving it for a filtered request would report the whole cube as if it
 * were narrowed. That is a superset, so nothing would fail loudly -- it would just destroy every
 * narrowing decision the consumer makes.
 */
public enum UnfilteredAvailabilitySource {

    /**
     * Fetch the registry's {@code Actual} content constraint ({@code contentconstraint} on SDMX
     * 2.1, {@code dataconstraint} on SDMX 3.0). Measured on Eurostat at 5.6--40 KB and ~0.3 s,
     * against 1.8--139 MB and 1--71 s for the equivalent whole-cube probe, with identical
     * per-dimension coverage.
     * <p>
     * Requires the resolved constraint structure type to be present in
     * {@code structureEndpointConfig.supportedStructures}.
     */
    CONTENT_CONSTRAINT,

    /**
     * Probe with a wildcard key like any other request. For registries that expose no constraint
     * resource. Both request shapes then come from one mechanism, so the consumer's
     * "filtered is a subset of unfiltered" invariant holds by construction and no intersection
     * step is needed.
     */
    PROBE
}
