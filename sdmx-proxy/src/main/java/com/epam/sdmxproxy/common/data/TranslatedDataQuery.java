package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import java.time.Instant;

@Data
@Builder(toBuilder = true)
public class TranslatedDataQuery {
    private RegistryConfiguration registryConfiguration;
    private VersionSpecificRegistryConfiguration versionConfiguration;
    private String context;
    private String agencyID;
    private String resourceID;
    private String version;
    private String key;
    private MultiValueMap<String, String> filters;
    private Instant updatedAfter;
    private Integer firstNObservations;
    private Integer lastNObservations;
    /**
     * SDMX 2.1 {@code detail} parameter ({@code full}, {@code dataonly}, {@code serieskeysonly},
     * {@code nodata}). Not part of the client-facing SDMX 3.0 data API -- SDMX-REST 2.x replaced
     * it with {@code attributes} + {@code measures} -- so this is only ever set internally, by
     * the availability-emulation probe builder, and only for an SDMX 2.1 registry. See design 040.
     */
    private String detail;
    private String dimensionAtObservation;
    private String attributes;
    private String measures;
    private String includeHistory;
    private Integer limit;
    private Instant asOf;
    private boolean skipEmptySeries;
    private String startPeriod;
    private String endPeriod;
    private MediaType contentType;
    private SdmxFormat returnFormat;
}
