package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

import java.util.List;

/**
 * Configuration for a specific endpoint type (structure, data, or availability).
 * Contains the base URL, list of supported return formats, default format, and bypass flag.
 */
@Data
public class EndpointConfiguration {
    /**
     * Base URL for this endpoint type.
     * For structures: base URL for structure queries
     * For data: base URL for data queries
     * For availability: base URL for availability queries (usually same as structures)
     */
    private String url;

    /**
     * List of formats that this endpoint can return natively.
     * If bypass is enabled and requested format is in this list, data will be returned directly without conversion.
     */
    private List<ReturnFormat> supportedFormats;

    /**
     * Default format to use when bypass is disabled or requested format is not in supportedFormats.
     * This format will be requested from registry and then converted to the requested format.
     * If null, no default format is available and an error should be thrown.
     */
    private ReturnFormat defaultFormat;

    /**
     * Whether bypass is enabled for this endpoint.
     * If true and requested format is in supportedFormats, data will be returned directly without conversion.
     * If false or requested format is not in supportedFormats, defaultFormat will be used and conversion will occur.
     */
    private boolean bypassEnabled;

}
