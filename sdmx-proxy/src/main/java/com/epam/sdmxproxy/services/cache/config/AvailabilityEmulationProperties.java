package com.epam.sdmxproxy.services.cache.config;

import java.time.Duration;

import lombok.Data;

/**
 * TTL configuration for emulated availability responses -- see design 040.
 * <p>
 * Every entry stands in for at least one upstream call, and for the broadest keys that call is a
 * multi-megabyte, multi-second data probe. The consumer makes matters worse: it caches only its
 * unfiltered availability call, forbids caching a filtered one, and one of its two unfiltered
 * call sites bypasses its own cache entirely. So this is the only cache most emulated
 * availability requests will ever see.
 * <p>
 * The default is ready-response-like rather than structure-like: dimension coverage moves when
 * data is published, not when structures change.
 */
@Data
public class AvailabilityEmulationProperties {

    private Duration duration = Duration.ofHours(6);

    private Duration jitter = Duration.ofMinutes(30);
}
