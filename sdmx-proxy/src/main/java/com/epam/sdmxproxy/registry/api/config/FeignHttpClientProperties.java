package com.epam.sdmxproxy.registry.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for HTTP client settings loaded from application.yaml
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdmxproxy.feign.http.client")
public class FeignHttpClientProperties {
    /**
     * Connection timeout in milliseconds.
     * Default: 10000 (10 seconds)
     */
    private Integer connectTimeout = 10000;

    /**
     * Read timeout in milliseconds.
     * Default: 30000 (30 seconds)
     */
    private Integer readTimeout = 30000;

    /**
     * Write timeout in milliseconds.
     * Default: 30000 (30 seconds)
     */
    private Integer writeTimeout = 30000;

    /**
     * Maximum number of idle connections in the connection pool.
     * This is shared across all registries and hosts.
     * Active connections don't count toward this limit - only idle connections waiting to be reused.
     * <p>
     * With multiple registries (each potentially a different host) and concurrent requests,
     * a higher value improves performance by keeping more connections ready for reuse.
     * <p>
     * Default: 50
     * <p>
     * Note: OkHttp creates connections on-demand, so this only limits how many idle
     * connections are kept alive. If you have 10 registries and concurrent requests,
     * you may want to increase this value.
     */
    private Integer maxIdleConnections = 50;

    /**
     * Keep-alive duration in milliseconds.
     * Default: 300000 (5 minutes)
     */
    private Long keepAliveDuration = 300000L;

    /**
     * Enable connection pooling.
     * Default: true
     */
    private Boolean connectionPoolingEnabled = true;
}
