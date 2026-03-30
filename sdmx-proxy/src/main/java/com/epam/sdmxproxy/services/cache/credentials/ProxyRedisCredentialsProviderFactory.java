package com.epam.sdmxproxy.services.cache.credentials;

import com.epam.sdmxproxy.services.cache.config.AwsRedisProperties;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.cache.config.RedisAuthProvider;
import io.lettuce.core.RedisCredentialsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Factory that creates the appropriate {@link RedisCredentialsProvider}
 * based on the configured cloud authentication provider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyRedisCredentialsProviderFactory {

    private final CacheProperties cacheProperties;

    private static void requireNonBlank(String value, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required property is missing: set the " + envVar + " environment variable");
        }
    }

    /**
     * Creates a {@link RedisCredentialsProvider} for the configured cloud provider.
     *
     * @return credentials provider, or empty if provider is {@link RedisAuthProvider#NONE}
     * @throws IllegalArgumentException if required properties are missing for the configured provider
     */
    public Optional<RedisCredentialsProvider> create() {
        RedisAuthProvider provider = cacheProperties.getRedis().getProvider();
        return switch (provider) {
            case NONE -> {
                log.debug("No cloud Redis auth provider configured, using static credentials");
                yield Optional.empty();
            }
            case AZURE -> {
                log.info("Creating Azure Managed Identity Redis credentials provider");
                yield Optional.of(new AzureRedisCredentialsProvider());
            }
            case AWS -> {
                AwsRedisProperties aws = cacheProperties.getRedis().getAws();
                requireNonBlank(aws.getUserId(), "REDIS_AWS_USER_ID");
                requireNonBlank(aws.getRegion(), "REDIS_AWS_REGION");
                requireNonBlank(aws.getClusterName(), "REDIS_AWS_CLUSTER_NAME");
                log.info("Creating AWS ElastiCache IAM Redis credentials provider");
                yield Optional.of(new AwsRedisCredentialsProvider(aws));
            }
            case GCP -> {
                String serviceAccount = cacheProperties.getRedis().getGcp().getServiceAccount();
                requireNonBlank(serviceAccount, "REDIS_GCP_SERVICE_ACCOUNT");
                log.info("Creating GCP Memorystore IAM Redis credentials provider");
                yield Optional.of(new GcpRedisCredentialsProvider(serviceAccount));
            }
        };
    }
}
