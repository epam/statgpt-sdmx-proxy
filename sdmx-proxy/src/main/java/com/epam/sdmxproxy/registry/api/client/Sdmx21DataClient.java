package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;

import java.io.InputStream;
import java.util.Map;

public interface Sdmx21DataClient extends SdmxApiBase {

    @RequestLine("GET /{flowRef}")
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef
    );

    @RequestLine("GET /{flowRef}/{key}")
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef,
            @Param("key") String key
    );

    /**
     * The data path without the provider segment, for registries that answer 404 to any
     * {@code providerRef} -- Statistics Canada (design 043). Unlike the two-segment overload
     * above, it carries the query parameters.
     */
    @RequestLine("GET /{flowRef}/{key}")
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef,
            @Param("key") String key,
            @QueryMap Map<String, Object> queryMap
    );

    @RequestLine("GET /{flowRef}/{key}/{providerRef}")
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef,
            @Param("key") String key,
            @Param("providerRef") String providerRef,
            @QueryMap Map<String, Object> queryMap
    );
}
