package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.registry.api.client.SdmxApiBase;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.io.InputStream;

/**
 * Statistics Canada serves one structure resource: a bundle with the agency scheme, the dataflow,
 * the DSD, its codelists, and its concept scheme. See design 043.
 */
public interface StatCanStructureClient extends SdmxApiBase {

    @RequestLine("GET /structure/{dsdId}")
    @Headers("Accept: application/vnd.sdmx.structure+xml;version=2.1")
    InputStream getBundle(@Param("dsdId") String dsdId);
}
