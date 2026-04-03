package com.epam.sdmxproxy.configserver.config;

public enum ConfigSourceType {
    DIAL_STORAGE(Values.DIAL_STORAGE),
    FILESYSTEM(Values.FILESYSTEM);

    private final String value;

    ConfigSourceType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * String constants for use in annotations (e.g., @ConditionalOnProperty havingValue).
     * Annotation attributes require compile-time constants, so these can't be method calls.
     */
    public static final class Values {
        public static final String DIAL_STORAGE = "DIAL_STORAGE";
        public static final String FILESYSTEM = "FILESYSTEM";

        private Values() {
        }
    }
}
