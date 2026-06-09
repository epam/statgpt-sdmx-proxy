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
public class TranslatedAvailabilityQuery {
    private RegistryConfiguration registryConfiguration;
    private VersionSpecificRegistryConfiguration versionConfiguration;
    private String context;
    private String agencyID;
    private String resourceID;
    private String version;
    private String key;
    private String componentId;
    private MultiValueMap<String, String> filters;
    private Instant updatedAfter;
    private String mode;
    private String references;
    private String startPeriod;
    private String endPeriod;
    private String reportingYearStartDay;
    /**
     * Content type requested by client (from Accept header).
     */
    private MediaType contentType;
    /**
     * Return format to use when requesting data from registry.
     * Determined by QueryTranslator based on bypass logic and defaultFormat.
     */
    private SdmxFormat returnFormat;
}
