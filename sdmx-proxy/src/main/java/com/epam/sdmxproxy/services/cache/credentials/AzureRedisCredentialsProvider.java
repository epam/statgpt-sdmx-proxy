package com.epam.sdmxproxy.services.cache.credentials;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * Lettuce {@link RedisCredentialsProvider} that authenticates to Azure Cache for Redis
 * using Azure Managed Identity via {@link DefaultAzureCredential}.
 *
 * <p>Extracts the {@code oid} (Object ID) claim from the JWT access token as the Redis username,
 * and uses the raw token as the password. Tokens are cached and refreshed when approaching expiry.</p>
 *
 * @see <a href="https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/src/samples/Azure-Cache-For-Redis/Jedis/Azure-AAD-Authentication-With-Jedis.md">Azure Redis AAD auth reference</a>
 */
@Slf4j
public class AzureRedisCredentialsProvider implements RedisCredentialsProvider {

    static final TokenRequestContext TOKEN_REQUEST_CONTEXT = new TokenRequestContext().addScopes("https://redis.azure.com/.default");
    private static final long EXPIRATION_WINDOW_SECONDS = 120;
    private final DefaultAzureCredential credential;
    private final Supplier<OffsetDateTime> clock;

    private volatile OffsetDateTime expiresAt;
    private volatile RedisCredentials cachedCredentials;

    public AzureRedisCredentialsProvider() {
        this(new DefaultAzureCredentialBuilder().build(), OffsetDateTime::now);
    }

    AzureRedisCredentialsProvider(DefaultAzureCredential credential, Supplier<OffsetDateTime> clock) {
        this.credential = credential;
        this.clock = clock;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Extracts the {@code oid} (Object ID) from the JWT payload.
     */
    static String extractUsernameFromToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed JWT token: expected at least 2 dot-separated parts, got " + parts.length);
        }

        try {
            byte[] jsonBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode jwt = OBJECT_MAPPER.readTree(jsonBytes);
            JsonNode oid = jwt.get("oid");
            if (oid == null || !oid.isTextual()) {
                throw new IllegalArgumentException("JWT payload missing 'oid' claim");
            }
            return oid.asText();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JWT payload", e);
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

        log.debug("Requesting Azure Redis access token");
        AccessToken accessToken = credential.getTokenSync(TOKEN_REQUEST_CONTEXT);
        String token = accessToken.getToken();
        String username = extractUsernameFromToken(token);

        expiresAt = accessToken.getExpiresAt();
        cachedCredentials = RedisCredentials.just(username, token);
        log.debug("Azure Redis token acquired, expires at {}", expiresAt);
        return cachedCredentials;
    }

    private boolean isExpiringSoon() {
        if (expiresAt == null) {
            return true;
        }
        return clock.get().plusSeconds(EXPIRATION_WINDOW_SECONDS).isAfter(expiresAt);
    }
}
