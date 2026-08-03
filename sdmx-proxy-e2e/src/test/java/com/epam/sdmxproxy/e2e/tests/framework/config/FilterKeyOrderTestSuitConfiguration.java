package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Test config for the generic filter-to-key ordering pin in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}.
 *
 * <p>Clients always speak SDMX 3.0 to the proxy, so dimension narrowing arrives ID-keyed as
 * {@code c[DIM]=value}. Against an SDMX 2.1 registry the proxy has to fold those into a positional
 * key, and the only correct order is the one the DSD declares. Getting it wrong produces a key of
 * the right arity with every value under the wrong dimension -- the registry answers HTTP 200 with
 * an empty constraint, so a status check cannot see it.
 *
 * <p>Point {@link #dataflowUrn} at a dataflow whose declared dimension order differs from the
 * alphabetical order of its dimension IDs; otherwise the pin passes under either behaviour and
 * guards nothing. The suite asserts this precondition and fails loudly if it does not hold.
 *
 * <p>Absent config block -> the pin is skipped for that registry.
 */
@Data
@NoArgsConstructor
public class FilterKeyOrderTestSuitConfiguration {

    /** Required: dataflow to query (e.g. {@code "OECD.SDD.STES:DSD_STES@DF_BTS(4.0)"}). */
    private String dataflowUrn;

    /**
     * Required: {@code dimensionId -> value} narrowing sent as {@code c[dimensionId]=value}.
     * Pick a combination known to hold data, and at least two dimensions that alphabetical
     * ordering would move to different positions.
     */
    private Map<String, String> filters;

    /** Accept header for the proxy. Defaults to {@code application/vnd.sdmx.structure+json;version=2.0.0}. */
    private String mediaType;

    /**
     * Optional. When non-null, asserts the returned constraint reports at least this many
     * dimensions -- guards against a technically non-empty but degenerate constraint carrying
     * only {@code TIME_PERIOD}.
     */
    private Integer minConstrainedDimensions;
}
