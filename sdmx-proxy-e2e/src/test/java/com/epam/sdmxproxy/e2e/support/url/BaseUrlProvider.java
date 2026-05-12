package com.epam.sdmxproxy.e2e.support.url;

/**
 * Resolves the base URL of the sdmx-proxy under test.
 * <p>
 * Lookup order (first non-blank wins):
 * <ol>
 *     <li>System property {@code sdmxproxy.e2e.host}</li>
 *     <li>Environment variable {@code E2E_HOST}</li>
 *     <li>Default {@code http://localhost:8050}</li>
 * </ol>
 * <p>
 * The result is resolved on the first call and cached for the lifetime of the JVM.
 * Each gradle test task forks its own JVM, so the cache does not survive between
 * {@code ./gradlew} invocations.
 */
public final class BaseUrlProvider {

    private static final String SYSTEM_PROP = "sdmxproxy.e2e.host";
    private static final String ENV_VAR = "E2E_HOST";
    private static final String DEFAULT_URL = "http://localhost:8050";

    private static volatile String cached;

    private BaseUrlProvider() {
    }

    public static String getBaseUrl() {
        String value = cached;
        if (value == null) {
            synchronized (BaseUrlProvider.class) {
                value = cached;
                if (value == null) {
                    value = resolve();
                    cached = value;
                }
            }
        }
        return value;
    }

    private static String resolve() {
        String raw = System.getProperty(SYSTEM_PROP);
        if (isBlank(raw)) {
            raw = System.getenv(ENV_VAR);
        }
        if (isBlank(raw)) {
            raw = DEFAULT_URL;
        }
        return stripTrailingSlashes(raw.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }
}
