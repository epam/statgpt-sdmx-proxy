package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;

import java.io.InputStream;
import java.util.Map;

public interface Sdmx30AvailabilityClient extends SdmxApiBase {

    @RequestLine(
            "GET /{context}/{agencyID}/{resourceID}/{version}/{key}/{componentId}" +
                    "?updatedAfter={updatedAfter}" +
                    "&mode={mode}" +
                    "&references={references}" +
                    "&reportingYearStartDay={reportingYearStartDay}"
    )
    @Headers("Accept: {contentType}")
    InputStream getAvailability(
            @Param("contentType") String contentType,
            @Param("context") String context,
            @Param("agencyID") String agencyID,
            @Param("resourceID") String resourceID,
            @Param("version") String version,
            @Param("key") String key,
            @Param("componentId") String componentId,
            @QueryMap Map<String, String> filters,
            @Param("updatedAfter") String updatedAfter, // Format as ISO string
            @Param("mode") String mode,
            @Param("references") String references,
            @Param("reportingYearStartDay") String reportingYearStartDay
    );
}
