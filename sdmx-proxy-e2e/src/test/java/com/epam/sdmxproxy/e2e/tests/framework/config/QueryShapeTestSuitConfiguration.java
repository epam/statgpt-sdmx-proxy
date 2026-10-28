package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Test config for the generic query-shape pins in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}.
 *
 * <p>Each pin covers a request shape that StatGPT sends and that the registry suites otherwise
 * never issue: a data key with a {@code +} OR, an availability POST that carries filters but no
 * key, and a {@code TIME_PERIOD} filter that matches no period of the dataflow. Each field enables
 * its own pin; a null field skips that pin.
 *
 * <p>Absent config block -> every pin is skipped for that registry.
 */
@Data
@NoArgsConstructor
public class QueryShapeTestSuitConfiguration {

    /** Required: dataflow to query (e.g. {@code "ESTAT:EF_LU_PCOLIVE(1.0)"}). */
    private String dataflowUrn;

    /**
     * Data key with at least one {@code +}-joined OR position, known to hold data
     * (e.g. {@code "*.TOTAL.TOTAL.G03A+G03+G03B.*.IT"}). The pin expects HTTP 200 with a body.
     */
    private String orKey;

    /**
     * {@code componentCode -> value} sent as an availability POST body with no {@code key}, the
     * shape StatGPT uses when indexing. The pin expects HTTP 200 with a constraint that reports
     * at least one dimension.
     */
    private Map<String, String> keylessAvailabilityFilters;

    /**
     * {@code c[TIME_PERIOD]} value that matches no period of the dataflow
     * (e.g. {@code "ge:2023+le:2026"}). The data and availability pins expect HTTP 200, not an
     * upstream rejection.
     */
    private String emptyTimeRange;

    /**
     * When true, the availability pin for {@link #emptyTimeRange} also asserts that the constraint
     * reports every non-time dimension with no values. Pins that emulated SDMX 2.1 availability
     * carries the time window into its data probe (design 043, B8): without it the probe ignores
     * the window and reports every code.
     */
    private boolean expectEmptyAvailabilityForEmptyTimeRange;

    /**
     * When true, {@code data/dataflow/{dataflowUrn}/*} with no filter returns HTTP 200 with at least
     * one series. Pins the match-all key expansion for registries that reject {@code all}
     * (design 043, R2).
     */
    private boolean unfilteredData;

    /**
     * A data key at least 4,000 characters long, an OR over many codes of one dimension, known to
     * hold data. The pin expects HTTP 200 with a body (design 043, R2: the expanded match-all key
     * can be long). Queried on {@link #longOrKeyDataflowUrn}, or on {@link #dataflowUrn} when that
     * is null.
     */
    private String longOrKey;

    /** Dataflow for {@link #longOrKey}; defaults to {@link #dataflowUrn}. */
    private String longOrKeyDataflowUrn;

    /**
     * When true, the {@link #orKey} request in SDMX-JSON 1.0 and 2.0 must key every series with
     * {@code :}-separated indexes, as the SDMX-JSON format requires (design 043, R3).
     */
    private boolean jsonSeriesKeyFormat;

    /** Accept header for data requests. Defaults to {@code application/vnd.sdmx.data+json;version=2.0.0}. */
    private String dataMediaType;

    /** Accept header for availability requests. Defaults to {@code application/vnd.sdmx.structure+json;version=2.0.0}. */
    private String availabilityMediaType;
}
