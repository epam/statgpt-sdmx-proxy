package com.epam.sdmxproxy.services.cache.credentials;

import com.epam.sdmxproxy.exception.IllegalRegistryConfigurationException;
import com.epam.sdmxproxy.services.cache.config.AwsRedisProperties;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.cache.config.GcpRedisProperties;
import com.epam.sdmxproxy.services.cache.config.RedisAuthProvider;
import com.epam.sdmxproxy.services.cache.config.RedisProperties;
import io.lettuce.core.RedisCredentialsProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRedisCredentialsProviderFactoryTest {

    private static CacheProperties buildCacheProperties(RedisAuthProvider provider) {
        AwsRedisProperties aws = new AwsRedisProperties();
        aws.setUserId("test-user");
        aws.setRegion("us-east-1");
        aws.setClusterName("test-cluster");

        GcpRedisProperties gcp = new GcpRedisProperties();
        gcp.setServiceAccount("test@project.iam.gserviceaccount.com");

        RedisProperties redis = new RedisProperties();
        redis.setProvider(provider);
        redis.setAws(aws);
        redis.setGcp(gcp);

        CacheProperties cacheProperties = new CacheProperties();
        cacheProperties.setRedis(redis);
        return cacheProperties;
    }

    @Test
    void shouldReturnEmptyForNoneProvider() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.NONE);
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        Optional<RedisCredentialsProvider> result = factory.create();

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnAzureProviderForAzure() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.AZURE);
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        Optional<RedisCredentialsProvider> result = factory.create();

        assertTrue(result.isPresent());
        assertInstanceOf(AzureRedisCredentialsProvider.class, result.get());
    }

    @Test
    void shouldReturnAwsProviderForAws() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.AWS);
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        Optional<RedisCredentialsProvider> result = factory.create();

        assertTrue(result.isPresent());
        assertInstanceOf(AwsRedisCredentialsProvider.class, result.get());
    }

    @Test
    void shouldReturnGcpProviderForGcp() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.GCP);
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        Optional<RedisCredentialsProvider> result = factory.create();

        assertTrue(result.isPresent());
        assertInstanceOf(GcpRedisCredentialsProvider.class, result.get());
    }

    @Test
    void shouldThrowWhenAwsUserIdMissing() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.AWS);
        cacheProperties.getRedis().getAws().setUserId("");
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        IllegalRegistryConfigurationException ex = assertThrows(IllegalRegistryConfigurationException.class, factory::create);
        assertTrue(ex.getMessage().contains("REDIS_AWS_USER_ID"));
    }

    @Test
    void shouldThrowWhenAwsRegionMissing() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.AWS);
        cacheProperties.getRedis().getAws().setRegion(null);
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        IllegalRegistryConfigurationException ex = assertThrows(IllegalRegistryConfigurationException.class, factory::create);
        assertTrue(ex.getMessage().contains("REDIS_AWS_REGION"));
    }

    @Test
    void shouldThrowWhenGcpServiceAccountMissing() {
        CacheProperties cacheProperties = buildCacheProperties(RedisAuthProvider.GCP);
        cacheProperties.getRedis().getGcp().setServiceAccount("");
        ProxyRedisCredentialsProviderFactory factory = new ProxyRedisCredentialsProviderFactory(cacheProperties);

        IllegalRegistryConfigurationException ex = assertThrows(IllegalRegistryConfigurationException.class, factory::create);
        assertTrue(ex.getMessage().contains("REDIS_GCP_SERVICE_ACCOUNT"));
    }
}
