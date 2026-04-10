package com.epam.sdmxproxy.registry.configuration.configserver;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import feign.Headers;
import feign.Param;
import feign.RequestLine;

public interface ConfigServerFeignApi {

    @RequestLine("GET /statgpt/sdmx-proxy-config-server/api/v0/config")
    @Headers("X-API-Key: {apiKey}")
    ProxyConfiguration getConfig(@Param("apiKey") String apiKey);
}
