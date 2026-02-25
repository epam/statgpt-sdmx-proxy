package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.MediaType;

/**
 * Result of parsing media type from Accept header.
 * Contains both SDMX version and media type information.
 */
@Data
@Builder
public class MediaTypeParseResult {
    private SdmxVersion sdmxVersion;
    private MediaType mediaType;
}
