package com.epam.sdmxproxy.services.cache.credentials;

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.protobuf.Timestamp;
import io.lettuce.core.RedisCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcpRedisCredentialsProviderTest {

    private static final String SERVICE_ACCOUNT = "test@project.iam.gserviceaccount.com";

    @Mock
    private IamCredentialsClient iamCredentialsClient;

    private static Clock fixedClock(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private GenerateAccessTokenResponse buildResponse(String token, long expiryEpochSeconds) {
        return GenerateAccessTokenResponse.newBuilder()
                .setAccessToken(token)
                .setExpireTime(Timestamp.newBuilder().setSeconds(expiryEpochSeconds).build())
                .build();
    }

    @Test
    void shouldResolveCredentials() {
        // Clock at t=1000, token expires at t=2000 (well in the future)
        when(iamCredentialsClient.generateAccessToken(eq(SERVICE_ACCOUNT), anyList(), anyList(), any()))
                .thenReturn(buildResponse("gcp-token-123", 2000));

        GcpRedisCredentialsProvider provider = new GcpRedisCredentialsProvider(SERVICE_ACCOUNT, () -> iamCredentialsClient, fixedClock(1000));

        RedisCredentials result = provider.resolveCredentials().block();

        assertNotNull(result);
        assertNull(result.getUsername());
        assertEquals("gcp-token-123", new String(result.getPassword()));
    }

    @Test
    void shouldCacheTokenAndReuseWithinExpiry() {
        when(iamCredentialsClient.generateAccessToken(eq(SERVICE_ACCOUNT), anyList(), anyList(), any()))
                .thenReturn(buildResponse("gcp-token-123", 2000));

        GcpRedisCredentialsProvider provider = new GcpRedisCredentialsProvider(SERVICE_ACCOUNT, () -> iamCredentialsClient, fixedClock(1000));

        RedisCredentials first = provider.resolveCredentials().block();
        RedisCredentials second = provider.resolveCredentials().block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(new String(first.getPassword()), new String(second.getPassword()));
        verify(iamCredentialsClient, times(1)).generateAccessToken(eq(SERVICE_ACCOUNT), anyList(), anyList(), any());
    }

    @Test
    void shouldRefreshTokenWhenApproachingExpiry() {
        // First token expires at t=1200, second at t=3000
        when(iamCredentialsClient.generateAccessToken(eq(SERVICE_ACCOUNT), anyList(), anyList(), any()))
                .thenReturn(buildResponse("first-token", 1200))
                .thenReturn(buildResponse("second-token", 3000));

        // Mutable clock starting at t=1000
        AtomicReference<Clock> clockRef = new AtomicReference<>(fixedClock(1000));
        Clock delegatingClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockRef.get().instant();
            }
        };

        GcpRedisCredentialsProvider provider = new GcpRedisCredentialsProvider(SERVICE_ACCOUNT, () -> iamCredentialsClient, delegatingClock);

        // First call — gets first token
        RedisCredentials first = provider.resolveCredentials().block();
        assertNotNull(first);
        assertEquals("first-token", new String(first.getPassword()));

        // Advance clock to t=1090 — within 120s buffer of expiry at 1200, should refresh
        clockRef.set(fixedClock(1090));

        RedisCredentials second = provider.resolveCredentials().block();
        assertNotNull(second);
        assertEquals("second-token", new String(second.getPassword()));

        verify(iamCredentialsClient, times(2)).generateAccessToken(eq(SERVICE_ACCOUNT), anyList(), anyList(), any());
    }

    @Test
    void shouldNotSupportStreaming() {
        GcpRedisCredentialsProvider provider = new GcpRedisCredentialsProvider(SERVICE_ACCOUNT, () -> iamCredentialsClient, fixedClock(1000));
        assertFalse(provider.supportsStreaming());
    }
}
