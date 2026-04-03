package com.epam.sdmxproxy.registry.api.client;

import feign.Headers;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;
import org.springframework.util.MultiValueMap;

import java.io.InputStream;

public interface Sdmx30DataClient extends SdmxApiBase {

    @RequestLine(
            "GET /{context}/{agencyID}/{resourceID}/{version}/{key}" +
                    "?updatedAfter={updatedAfter}" +
                    "&firstNObservations={firstNObservations}" +
                    "&lastNObservations={lastNObservations}" +
                    "&dimensionAtObservation={dimensionAtObservation}" +
                    "&attributes={attributes}" +
                    "&measures={measures}" +
                    "&includeHistory={includeHistory}" +
                    "&limit={limit}" +
                    "&asOf={asOf}" +
                    "&skipEmptySeries={skipEmptySeries}"
    )
    @Headers("Accept: {contentType}")
    InputStream getData(
            @Param("contentType") String contentType,
            @Param("context") String context,
            @Param("agencyID") String agencyID,
            @Param("resourceID") String resourceID,
            @Param("version") String version,
            @Param("key") String key,
            @Param("updatedAfter") String updatedAfter, // Format as ISO string
            @Param("firstNObservations") Integer firstNObservations,
            @Param("lastNObservations") Integer lastNObservations,
            @Param("dimensionAtObservation") String dimensionAtObservation,
            @Param("attributes") String attributes,
            @Param("measures") String measures,
            @Param("includeHistory") String includeHistory,
            @Param("limit") Integer limit,
            @Param("asOf") String asOf, // Format as ISO string
            @Param("skipEmptySeries") Boolean skipEmptySeries,
            @QueryMap MultiValueMap<String, String> filters
    );
}
