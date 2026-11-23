package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.List;

/**
 * One preheat group: the dataflows to keep warm for one client media type. See design 044.
 */
@Data
public class PreheatConfiguration {

    /**
     * Client {@code Accept} value to warm and split into, for example
     * {@code application/vnd.sdmx.structure+xml;version=2.1}. Its {@code version} parameter
     * selects the SDMX version exactly as a client request's Accept does.
     */
    private String mediaType;

    /**
     * Dataflow references in the form {@code AGENCY:ID(VERSION)}, for example
     * {@code OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)}.
     */
    private List<String> dataflows;
}
