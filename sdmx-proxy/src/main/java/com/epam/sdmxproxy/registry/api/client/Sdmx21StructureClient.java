package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.io.InputStream;

public interface Sdmx21StructureClient extends SdmxApiBase {

    @RequestLine("GET /{structureType}/{agency}/{id}?references={references}&detail={detail}")
    @Headers("Accept: {contentType}")
    InputStream getStructures(
            @Param("contentType") String contentType,
            @Param("structureType") String structureType,
            @Param("agency") String agency,
            @Param("id") String id,
            @Param("references") String references,
            @Param("detail") String detail
    );

    @RequestLine("GET /{structureType}/{agency}/{id}/{version}?references={references}&detail={detail}")
    @Headers("Accept: {contentType}")
    InputStream getStructures(
            @Param("contentType") String contentType,
            @Param("structureType") String structureType,
            @Param("agency") String agency,
            @Param("id") String id,
            @Param("version") String version,
            @Param("references") String references,
            @Param("detail") String detail
    );
}
