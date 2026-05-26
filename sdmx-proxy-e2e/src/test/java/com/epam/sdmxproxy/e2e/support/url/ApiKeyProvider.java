package com.epam.sdmxproxy.e2e.support.url;

/**
 * Resolves the optional DIAL {@code Api-Key} used to authenticate against a DIAL-fronted
 * sdmx-proxy deployment (review / test envs). Read once from {@code E2E_PASSWORD} and
 * cached for the JVM lifetime.
 * <p>
 * When the variable is unset or blank, {@link #getApiKey()} returns {@code null} and
 * callers must omit the header entirely (local runs against a bare proxy do not expect
 * any auth header).
 */
public final class ApiKeyProvider {

    private static final String ENV_VAR = "E2E_PASSWORD";

    private static volatile String cached;
    private static volatile boolean resolved;

    private ApiKeyProvider() {
    }

    public static String getApiKey() {
        if (!resolved) {
            synchronized (ApiKeyProvider.class) {
                if (!resolved) {
                    cached = resolve();
                    resolved = true;
                }
            }
        }
        return cached;
    }

    private static String resolve() {
        String raw = System.getenv(ENV_VAR);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
