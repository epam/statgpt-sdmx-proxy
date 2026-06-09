package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.MediaType;

@Data
@Builder
public class TranslatedStructureQuery {
    private RegistryConfiguration registryConfiguration;
    private VersionSpecificRegistryConfiguration versionConfiguration;
    private Structure structure;
    private String references;
    private String detail;
    /**
     * Content type requested by client (from Accept header).
     */
    private MediaType contentType;
    /**
     * Return format to use when requesting data from registry.
     * Determined by QueryTranslator based on bypass logic and defaultFormat.
     */
    private SdmxFormat registryReturnFormat;
}
