package com.epam.sdmxproxy.services.cache.credentials;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.epam.sdmxproxy.exception.CredentialProvisioningException;
import com.epam.sdmxproxy.services.cache.config.AwsRedisProperties;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;

/**
 * Lettuce {@link RedisCredentialsProvider} for AWS ElastiCache IAM authentication.
 *
 * <p>Generates a SigV4-presigned token used as the Redis password. Tokens are cached
 * and refreshed when approaching expiry (120-second buffer before 900-second token lifetime).</p>
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonElastiCache/latest/red-ug/auth-iam.html">
 * AWS ElastiCache IAM Authentication</a>
 */
@Slf4j
public class AwsRedisCredentialsProvider implements RedisCredentialsProvider {

    private static final long TOKEN_EXPIRY_SECONDS = 900;
    private static final long EXPIRATION_WINDOW_SECONDS = 120;

    private final String userId;
    private final IamAuthTokenRequest iamAuthTokenRequest;
    private final AWSCredentialsProvider awsCredentialsProvider;
    private final Clock clock;

    private volatile Instant expiresAt;
    private volatile RedisCredentials cachedCredentials;

    public AwsRedisCredentialsProvider(AwsRedisProperties properties) {
        this(
                properties.getUserId(),
                new IamAuthTokenRequest(
                        properties.getUserId(),
                        properties.getClusterName(),
                        properties.getRegion(),
                        properties.isServerless()),
                DefaultAWSCredentialsProviderChain.getInstance(),
                Clock.systemUTC()
        );
    }

    AwsRedisCredentialsProvider(String userId, IamAuthTokenRequest iamAuthTokenRequest,
                                AWSCredentialsProvider awsCredentialsProvider, Clock clock) {
        this.userId = userId;
        this.iamAuthTokenRequest = iamAuthTokenRequest;
        this.awsCredentialsProvider = awsCredentialsProvider;
        this.clock = clock;
    }

    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        RedisCredentials current = cachedCredentials;
        if (current != null && !isExpiringSoon()) {
            return Mono.just(current);
        }
        return Mono.fromCallable(this::refreshToken);
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    private synchronized RedisCredentials refreshToken() {
        if (cachedCredentials != null && !isExpiringSoon()) {
            return cachedCredentials;
        }

        try {
            log.debug("Generating AWS ElastiCache IAM auth token");
            String token = iamAuthTokenRequest.toSignedRequestUri(awsCredentialsProvider.getCredentials());
            cachedCredentials = RedisCredentials.just(userId, token);
            expiresAt = clock.instant().plusSeconds(TOKEN_EXPIRY_SECONDS);
            log.debug("AWS ElastiCache IAM token generated, expires at {}", expiresAt);
            return cachedCredentials;
        } catch (URISyntaxException e) {
            throw new CredentialProvisioningException("Failed to generate ElastiCache IAM auth token", e);
        }
    }

    private boolean isExpiringSoon() {
        if (expiresAt == null) {
            return true;
        }
        return clock.instant().plusSeconds(EXPIRATION_WINDOW_SECONDS).isAfter(expiresAt);
    }
}
