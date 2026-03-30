package com.epam.sdmxproxy.services.cache.config;

import com.epam.sdmxproxy.services.cache.credentials.ProxyRedisCredentialsProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisCredentialsProvider;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Optional;

/**
 * Redis configuration for caching.
 * Only active when cache mode is 'redis'.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sdmxproxy.cache.mode", havingValue = "redis")
public class RedisConfig {

    private final CacheProperties cacheProperties;
    private final ProxyRedisCredentialsProviderFactory credentialsProviderFactory;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(cacheProperties.getRedis().getHost());
        config.setPort(cacheProperties.getRedis().getPort());

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();

        Optional<RedisCredentialsProvider> credProvider = credentialsProviderFactory.create();
        if (credProvider.isPresent()) {
            RedisCredentialsProvider provider = credProvider.get();
            builder.redisCredentialsProviderFactory(new RedisCredentialsProviderFactory() {
                @Override
                public RedisCredentialsProvider createCredentialsProvider(RedisConfiguration redisConfig) {
                    return provider;
                }
            });
            builder.useSsl();
            log.info("Redis configured with cloud provider credentials and SSL");
        } else {
            if (cacheProperties.getRedis().getPassword() != null
                    && !cacheProperties.getRedis().getPassword().isEmpty()) {
                config.setPassword(cacheProperties.getRedis().getPassword());
            }
            if (cacheProperties.getRedis().isSsl()) {
                builder.useSsl();
            }
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, builder.build());
        factory.setValidateConnection(true);
        log.info("Redis connection configured: {}:{}", config.getHostName(), config.getPort());
        return factory;
    }

    @Bean
    public RedisTemplate<String, SdmxBeans> parsedStructuresRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        RedisTemplate<String, SdmxBeans> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for POJO
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisTemplate<String, byte[]> readyResponseRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(RedisSerializer.byteArray());

        template.afterPropertiesSet();
        return template;
    }
}
