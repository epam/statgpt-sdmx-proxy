package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.io.InputStream;

public interface Sdmx21AvailabilityClient extends SdmxApiBase {

    @RequestLine(
            "GET /availableconstraint/{flowRef}/{key}/{providerRef}/{componentId}" +
                    "?startPeriod={startPeriod}" +
                    "&endPeriod={endPeriod}" +
                    "&updatedAfter={updatedAfter}" +
                    "&mode={mode}" +
                    "&references={references}"
    )
    @Headers("Accept: {contentType}")
    InputStream getAvailability(
            @Param("contentType") String contentType,
            @Param("flowRef") String flowRef,
            @Param("key") String key,
            @Param("providerRef") String providerRef,
            @Param("componentId") String componentId,
            @Param("startPeriod") String startPeriod,
            @Param("endPeriod") String endPeriod,
            @Param("updatedAfter") String updatedAfter, // Format as ISO string
            @Param("mode") String mode,
            @Param("references") String references
    );
}