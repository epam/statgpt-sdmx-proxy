package com.epam.sdmxproxy.registry.configuration;

/**
 * Published after the effective proxy configuration changed. See design 044.
 *
 * @param source            where the change came from, for logs
 * @param cachesInvalidated whether the change touched anything besides {@code preheat} and the
 *                          caches were therefore invalidated
 */
public record ProxyConfigurationChangedEvent(String source, boolean cachesInvalidated) {
}
