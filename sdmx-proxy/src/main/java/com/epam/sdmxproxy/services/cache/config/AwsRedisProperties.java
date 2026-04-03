package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

/**
 * AWS ElastiCache IAM authentication properties (Phase 2).
 */
@Data
public class AwsRedisProperties {
    private String userId;
    private String region;
    private String clusterName;
    private boolean serverless = false;
}
