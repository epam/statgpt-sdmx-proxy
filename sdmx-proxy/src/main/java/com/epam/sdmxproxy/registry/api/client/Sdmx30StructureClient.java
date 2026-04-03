package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.io.InputStream;

public interface Sdmx30StructureClient extends SdmxApiBase {

    //TODO CHECK THAT IT ALIGNS WITH 3.0 API
    @RequestLine("GET /{structureType}/{agencyId}/{resourceId}/{version}?references={references}&detail={detail}")
    @Headers("Accept: {contentType}")
    InputStream getStructures(
            @Param("contentType") String contentType,
            @Param("structureType") String structureType,
            @Param("agencyId") String agencyId,
            @Param("resourceId") String resourceId,
            @Param("version") String version,
            @Param("references") String references,
            @Param("detail") String detail
    );
}
