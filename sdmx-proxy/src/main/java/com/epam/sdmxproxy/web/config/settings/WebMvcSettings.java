package com.epam.sdmxproxy.web.config.settings;

import org.springframework.stereotype.Component;

@Component
public class WebMvcSettings {
    public static final String API_PREFIX = "/statgpt/sdmx-proxy/api/v0";
    public static final String INTERNAL_API_PREFIX = "/statgpt/sdmx-proxy/api/v0";

    public String getApiPrefix() {
        return API_PREFIX;
    }

    public String getInternalApiPrefix() {
        return INTERNAL_API_PREFIX;
    }
}
