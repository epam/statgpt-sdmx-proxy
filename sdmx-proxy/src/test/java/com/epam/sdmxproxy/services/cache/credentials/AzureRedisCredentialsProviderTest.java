package com.epam.sdmxproxy.services.cache.credentials;

import com.azure.core.credential.AccessToken;
import com.azure.identity.DefaultAzureCredential;
import io.lettuce.core.RedisCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureRedisCredentialsProviderTest {

    @Mock
    private DefaultAzureCredential defaultAzureCredential;

    private static String buildJwtToken(String oid) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"oid\":\"" + oid + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    @Test
    void shouldResolveCredentialsFromAzureToken() {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusMinutes(60);

        String token = buildJwtToken("test-oid-123");
        when(defaultAzureCredential.getTokenSync(eq(AzureRedisCredentialsProvider.TOKEN_REQUEST_CONTEXT)))
                .thenReturn(new AccessToken(token, expiresAt));

        AzureRedisCredentialsProvider provider = new AzureRedisCredentialsProvider(defaultAzureCredential, () -> now);

        RedisCredentials credentials = provider.resolveCredentials().block();

        assertNotNull(credentials);
        assertEquals("test-oid-123", credentials.getUsername());
        assertNotNull(credentials.getPassword());
        assertEquals(token, new String(credentials.getPassword()));
    }

    @Test
    void shouldCacheTokenAndReuseWithinExpiryWindow() {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusMinutes(60);

        String token = buildJwtToken("cached-oid");
        when(defaultAzureCredential.getTokenSync(eq(AzureRedisCredentialsProvider.TOKEN_REQUEST_CONTEXT)))
                .thenReturn(new AccessToken(token, expiresAt));

        AzureRedisCredentialsProvider provider = new AzureRedisCredentialsProvider(defaultAzureCredential, () -> now);

        RedisCredentials first = provider.resolveCredentials().block();
        RedisCredentials second = provider.resolveCredentials().block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getUsername(), second.getUsername());
        verify(defaultAzureCredential, times(1)).getTokenSync(eq(AzureRedisCredentialsProvider.TOKEN_REQUEST_CONTEXT));
    }

    @Test
    void shouldRefreshTokenWhenApproachingExpiry() {
        AtomicReference<OffsetDateTime> currentTime = new AtomicReference<>(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1000), ZoneOffset.UTC));

        OffsetDateTime firstExpiry = currentTime.get().plusSeconds(130);
        OffsetDateTime secondExpiry = currentTime.get().plusMinutes(60);

        String firstToken = buildJwtToken("first-oid");
        String secondToken = buildJwtToken("second-oid");

        when(defaultAzureCredential.getTokenSync(eq(AzureRedisCredentialsProvider.TOKEN_REQUEST_CONTEXT)))
                .thenReturn(new AccessToken(firstToken, firstExpiry))
                .thenReturn(new AccessToken(secondToken, secondExpiry));

        AzureRedisCredentialsProvider provider = new AzureRedisCredentialsProvider(
                defaultAzureCredential, currentTime::get);

        // First call — gets first token
        RedisCredentials first = provider.resolveCredentials().block();
        assertNotNull(first);
        assertEquals("first-oid", first.getUsername());

        // Advance time past expiration window (120s before expiry)
        currentTime.set(OffsetDateTime.ofInstant(Instant.ofEpochSecond(1020), ZoneOffset.UTC));

        // Second call — should refresh
        RedisCredentials second = provider.resolveCredentials().block();
        assertNotNull(second);
        assertEquals("second-oid", second.getUsername());

        verify(defaultAzureCredential, times(2)).getTokenSync(eq(AzureRedisCredentialsProvider.TOKEN_REQUEST_CONTEXT));
    }

    @Test
    void shouldNotSupportStreaming() {
        AzureRedisCredentialsProvider provider = new AzureRedisCredentialsProvider(defaultAzureCredential, OffsetDateTime::now);
        assertFalse(provider.supportsStreaming());
    }

    @Test
    void shouldExtractOidFromJwtToken() {
        String token = buildJwtToken("my-object-id");
        String oid = AzureRedisCredentialsProvider.extractUsernameFromToken(token);
        assertEquals("my-object-id", oid);
    }

    @Test
    void shouldHandleBase64PaddingVariants() {
        // Test with payload that needs padding
        String shortOid = "ab";
        String token = buildJwtToken(shortOid);
        assertEquals(shortOid, AzureRedisCredentialsProvider.extractUsernameFromToken(token));

        String longerOid = "abc";
        token = buildJwtToken(longerOid);
        assertEquals(longerOid, AzureRedisCredentialsProvider.extractUsernameFromToken(token));
    }

    @Test
    void shouldThrowOnMalformedToken() {
        assertThrows(IllegalArgumentException.class,
                () -> AzureRedisCredentialsProvider.extractUsernameFromToken("no-dots-here"));
    }
}
