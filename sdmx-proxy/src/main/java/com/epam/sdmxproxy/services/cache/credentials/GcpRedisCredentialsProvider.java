package com.epam.sdmxproxy.services.cache.credentials;

import com.epam.sdmxproxy.exception.CredentialProvisioningException;
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.IamCredentialsSettings;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lettuce {@link RedisCredentialsProvider} for GCP Memorystore IAM authentication.
 *
 * <p>Uses {@link IamCredentialsClient} to generate short-lived access tokens (15-minute lifetime)
 * for the configured service account. Tokens are cached and refreshed when approaching expiry
 * (120-second buffer).</p>
 *
 * @see <a href="https://cloud.google.com/memorystore/docs/redis/manage-iam-auth">
 * GCP Memorystore IAM Authentication</a>
 */
@Slf4j
public class GcpRedisCredentialsProvider implements RedisCredentialsProvider {

    private static final Duration LIFETIME = Duration.newBuilder()
            .setSeconds(TimeUnit.MINUTES.toSeconds(15))
            .build();

    private static final List<String> SCOPES = Collections.singletonList("https://www.googleapis.com/auth/cloud-platform");
    private static final long EXPIRATION_WINDOW_SECONDS = 120;

    private final Supplier<IamCredentialsClient> iamClientSupplier;
    private final String serviceAccount;
    private final Clock clock;

    private volatile IamCredentialsClient iamClient;
    private volatile Instant expiresAt;
    private volatile RedisCredentials cachedCredentials;

    public GcpRedisCredentialsProvider(String serviceAccount) {
        this(serviceAccount, GcpRedisCredentialsProvider::createDefaultIamClient, Clock.systemUTC());
    }

    GcpRedisCredentialsProvider(String serviceAccount, Supplier<IamCredentialsClient> iamClientSupplier, Clock clock) {
        this.serviceAccount = serviceAccount;
        this.iamClientSupplier = iamClientSupplier;
        this.clock = clock;
    }

    private static IamCredentialsClient createDefaultIamClient() {
        try {
            IamCredentialsSettings settings = IamCredentialsSettings.newHttpJsonBuilder().build();
            return IamCredentialsClient.create(settings);
        } catch (IOException e) {
            throw new CredentialProvisioningException("Failed to create GCP IAM credentials client", e);
        }
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

        if (iamClient == null) {
            iamClient = iamClientSupplier.get();
        }

        log.debug("Requesting GCP Memorystore access token for {}", serviceAccount);
        GenerateAccessTokenResponse response = iamClient.generateAccessToken(
                serviceAccount,
                Collections.emptyList(),
                SCOPES,
                LIFETIME);

        Timestamp expireTime = response.getExpireTime();
        String token = response.getAccessToken();
        expiresAt = Instant.ofEpochSecond(expireTime.getSeconds());
        cachedCredentials = RedisCredentials.just(null, token);
        log.debug("GCP Memorystore token acquired, expires at {}", expiresAt);
        return cachedCredentials;
    }

    private boolean isExpiringSoon() {
        if (expiresAt == null) {
            return true;
        }
        return clock.instant().plusSeconds(EXPIRATION_WINDOW_SECONDS).isAfter(expiresAt);
    }
}
