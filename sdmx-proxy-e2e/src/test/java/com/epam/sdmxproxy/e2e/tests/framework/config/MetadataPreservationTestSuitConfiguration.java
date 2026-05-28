package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Test config for the generic MSD-derived metadata-attribute preservation pin in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}. Picks a single
 * dataflow whose DSD is known to reference an MSD via {@code metadataAttributeUsages};
 * the base suite hits that dataflow twice (default attributes, then {@code attributes=all})
 * and pins:
 * <ul>
 *   <li>{@code attributes=all} attribute IDs are a strict superset of the default
 *       response IDs (no metadata attribute IDs leak when the client did not opt in),</li>
 *   <li>at least one MSD-derived attribute carries a non-null value in the
 *       {@code attributes=all} response (the PRESERVE_METADATA_ATTRIBUTES fixture's
 *       value-injection path is exercised, not just its definitions).</li>
 * </ul>
 * Absent field on a registry's test config -> the pin is skipped for that suite (the
 * registry either has no MSD usages or its data fixture is not yet configured).
 */
@Data
@NoArgsConstructor
public class MetadataPreservationTestSuitConfiguration {

    /** Dataflow to query (e.g. {@code "IMF.RES:WEO(9.0.0)"}). Must point at a DSD with MSD usages. */
    private String dataflowUrn;

    /** Dataflow key to query (e.g. {@code "USA.LP.*"}). Use {@code "*"} for everything. */
    private String key;

    /** Accept header for the proxy. Defaults to {@code application/vnd.sdmx.data+json;version=2.0.0}. */
    private String mediaType;
}
