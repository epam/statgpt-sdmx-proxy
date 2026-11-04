package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.registry.api.client.SdmxApiBase;

import feign.Headers;
import feign.RequestLine;

import java.io.InputStream;

/**
 * Statistics Canada Web Data Service (WDS), a non-SDMX JSON API. Only the table list is used: it
 * is the source of the emulated dataflow catalog. See design 043.
 */
public interface StatCanWdsClient extends SdmxApiBase {

    @RequestLine("GET /getAllCubesListLite")
    @Headers("Accept: application/json")
    InputStream getAllCubesListLite();
}
