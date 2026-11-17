package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

import java.time.Duration;

/**
 * Single-flight settings for registry structure fetches -- see design 042.
 */
@Data
public class SingleFlightProperties {

    /**
     * Upper bound on how long one caller waits for a shared upstream call. Kept under
     * {@code spring.mvc.async.request-timeout} so the proxy's own 503 reaches the client first.
     * Expiry abandons only the waiting caller; the upstream call runs on and fills the cache.
     */
    private Duration waitTimeout = Duration.ofSeconds(590);

    /**
     * How long a key that ended in HTTP 429 after the full retry budget fails fast without
     * calling the registry.
     */
    private Duration rateLimitCooldown = Duration.ofSeconds(30);
}
