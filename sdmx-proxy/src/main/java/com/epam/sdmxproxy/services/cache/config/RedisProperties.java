package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

/**
 * Redis connection configuration properties.
 */
@Data
public class RedisProperties {
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private boolean ssl = false;
}
