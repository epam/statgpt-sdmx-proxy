package com.epam.sdmxproxy.e2e.tests.framework.config;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Test config for the generic {@code limit}-related cases in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}. Per registry:
 * pick a dataflow and a small limit; the base suite runs two diagnostics against
 * every registry that declares this config:
 * <ul>
 *   <li>native-limit honored (soft: aborts with warning if the registry ignores the
 *       parameter, pointing operators at {@code supportsLimit: false}),</li>
 *   <li>emulation strict cap, parameterized per {@link #registryReturnFormats}
 *       (hard fail if emulation returns more than {@code limit} series).</li>
 * </ul>
 * Absent field on a registry's test config -> both tests are skipped for that suite.
 */
@Data
@NoArgsConstructor
public class LimitTestSuitConfiguration {

    /** Dataflow + key + optional filters used as the single test request. */
    private String urn;
    private String key;
    private Map<String, String> filters;

    /** Small limit value (e.g. 5-10) so the test can cleanly assert the cap. */
    private int limit;

    /** Optional; caps observations per series to keep response payload small. */
    private Integer firstNObservations;

    /** Accept header for the proxy. Must be a supported data media type. */
    private String mediaType;

    /**
     * Registry return formats to exercise the emulation test against. For each entry the
     * test POSTs a config with {@code dataEndpointConfig.defaultFormat} set to that value,
     * adds it to {@code supportedFormats}, then issues a request and parses the response
     * per-format to count series. Lets one config cover e.g. JSON 1.0 + CSV + XML (generic
     * data) + XML (structure-specific). {@code null} / empty -> test runs once using the
     * registry's existing {@code defaultFormat}.
     */
    private List<SdmxFormat> registryReturnFormats;
}
