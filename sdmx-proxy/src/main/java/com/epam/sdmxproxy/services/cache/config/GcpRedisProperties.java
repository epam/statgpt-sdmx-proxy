package com.epam.sdmxproxy.services.cache.config;

import lombok.Data;

/**
 * GCP Memorystore IAM authentication properties (Phase 3).
 */
@Data
public class GcpRedisProperties {
    private String serviceAccount;
}
