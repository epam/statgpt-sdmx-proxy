package com.epam.sdmxproxy.configuration.data.availability;

/**
 * Strategy used to answer availability requests for a registry whose availability endpoint does
 * not work ({@code availabilityEndpointConfig.availabilityEnabled: false}) -- see design 040.
 */
public enum AvailabilityEmulationType {

    /**
     * No emulation. Availability requests are rejected with HTTP 501, which is the SDMX-REST
     * status for an unimplemented method and strictly more informative than proxying whatever
     * the registry answers (Eurostat answers 405 on SDMX 2.1 and 404 on SDMX 3.0).
     */
    NONE,

    /**
     * Emulate availability from a series-key data query carrying the client's own key and
     * filters. Unfiltered requests may additionally be short-circuited to the registry's
     * {@code Actual} content constraint -- see {@link UnfilteredAvailabilitySource}.
     */
    DATA_QUERY
}
