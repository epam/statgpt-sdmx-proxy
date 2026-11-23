package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.services.cache.config.TtlProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Settings of the structure cache preheat -- see design 044.
 */
@Data
@Component
@ConfigurationProperties(prefix = "sdmxproxy.preheat")
public class PreheatProperties {

    /**
     * Off by default, so that {@code @SpringBootTest} contexts and the E2E {@code bootRun}, which
     * load the bundled configuration, never call live registries. Deployments set
     * {@code SDMXPROXY_PREHEAT_ENABLED=true}.
     */
    private boolean enabled;

    private Duration startDelay = Duration.ofSeconds(10);

    private Duration configChangeDebounce = Duration.ofSeconds(10);

    /**
     * Minimum time between two upstream calls made by preheat.
     */
    private Duration upstreamInterval = Duration.ofSeconds(4);

    private Duration refreshInterval = Duration.ofHours(1);

    /**
     * Base threshold: raw entries with less remaining TTL than this plus their per-key offset are
     * refreshed.
     */
    private Duration refreshAhead = Duration.ofHours(12);

    /**
     * Width of the per-key offset added to {@link #refreshAhead}, so that entries written together
     * are refreshed in different runs.
     */
    private Duration refreshSpread = Duration.ofHours(6);

    /**
     * Split ready responses with less remaining TTL than this are rebuilt from cached descendants.
     */
    private Duration readyRefreshAhead = Duration.ofHours(2);

    /**
     * Upper bound on refresh calls per run. Missing entries are fetched regardless.
     */
    private int maxRefreshesPerRun = 10;

    /**
     * Wait after a 429 or a cooldown before retrying the same dataflow.
     */
    private Duration rateLimitBackoff = Duration.ofSeconds(30);

    private int maxAttemptsPerDataflow = 3;

    /**
     * Rejects values that would refresh every entry on every run, or let entries expire between
     * two runs.
     *
     * @throws IllegalStateException naming the broken constraint
     */
    public void validate(TtlProperties ttl) {
        Duration rawTtl = ttl.getParsedStructures().getDuration();
        Duration rawJitter = ttl.getParsedStructures().getJitter();
        Duration readyTtl = ttl.getReadyResponses().getDuration();
        Duration readyJitter = ttl.getReadyResponses().getJitter();
        if (refreshAhead.plus(refreshSpread).compareTo(rawTtl) >= 0) {
            throw new IllegalStateException("sdmxproxy.preheat.refreshAhead + refreshSpread (" + refreshAhead.plus(refreshSpread) + ") must stay under the raw structures TTL " + rawTtl + ", otherwise every run refreshes every entry");
        }
        if (refreshAhead.compareTo(refreshInterval.plus(rawJitter)) <= 0) {
            throw new IllegalStateException("sdmxproxy.preheat.refreshAhead (" + refreshAhead + ") must exceed refreshInterval + raw structures jitter (" + refreshInterval.plus(rawJitter) + "), otherwise entries can expire between two runs");
        }
        if (readyRefreshAhead.compareTo(refreshInterval.plus(readyJitter)) <= 0 || readyRefreshAhead.compareTo(readyTtl) >= 0) {
            throw new IllegalStateException("sdmxproxy.preheat.readyRefreshAhead (" + readyRefreshAhead + ") must exceed refreshInterval + ready-response jitter (" + refreshInterval.plus(readyJitter) + ") and stay under the ready-response TTL " + readyTtl);
        }
    }
}
