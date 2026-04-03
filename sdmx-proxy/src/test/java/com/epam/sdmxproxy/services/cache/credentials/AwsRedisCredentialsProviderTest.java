package com.epam.sdmxproxy.services.cache.credentials;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import io.lettuce.core.RedisCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsRedisCredentialsProviderTest {

    @Mock
    private IamAuthTokenRequest iamAuthTokenRequest;

    @Mock
    private AWSCredentialsProvider awsCredentialsProvider;

    private static Clock fixedClock(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    @Test
    void shouldResolveCredentials() throws URISyntaxException {
        AWSCredentials credentials = mock(AWSCredentials.class);
        when(awsCredentialsProvider.getCredentials()).thenReturn(credentials);
        when(iamAuthTokenRequest.toSignedRequestUri(credentials)).thenReturn("signed-token-url");

        AwsRedisCredentialsProvider provider = new AwsRedisCredentialsProvider("test-user", iamAuthTokenRequest, awsCredentialsProvider, fixedClock(1000));

        RedisCredentials result = provider.resolveCredentials().block();

        assertNotNull(result);
        assertEquals("test-user", result.getUsername());
        assertEquals("signed-token-url", new String(result.getPassword()));
    }

    @Test
    void shouldCacheTokenAndReuseWithinExpiry() throws URISyntaxException {
        AWSCredentials credentials = mock(AWSCredentials.class);
        when(awsCredentialsProvider.getCredentials()).thenReturn(credentials);
        when(iamAuthTokenRequest.toSignedRequestUri(credentials)).thenReturn("signed-token-url");

        AwsRedisCredentialsProvider provider = new AwsRedisCredentialsProvider("test-user", iamAuthTokenRequest, awsCredentialsProvider, fixedClock(1000));

        RedisCredentials first = provider.resolveCredentials().block();
        RedisCredentials second = provider.resolveCredentials().block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getUsername(), second.getUsername());
        verify(awsCredentialsProvider, times(1)).getCredentials();
    }

    @Test
    void shouldRefreshTokenWhenApproachingExpiry() throws URISyntaxException {
        AWSCredentials credentials = mock(AWSCredentials.class);
        when(awsCredentialsProvider.getCredentials()).thenReturn(credentials);
        when(iamAuthTokenRequest.toSignedRequestUri(credentials))
                .thenReturn("first-token")
                .thenReturn("second-token");

        // Mutable clock: start at t=1000
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

        AwsRedisCredentialsProvider provider = new AwsRedisCredentialsProvider("test-user", iamAuthTokenRequest, awsCredentialsProvider, delegatingClock);

        // First call — gets first token. Token expires at t=1000+900=1900.
        RedisCredentials first = provider.resolveCredentials().block();
        assertNotNull(first);
        assertEquals("first-token", new String(first.getPassword()));

        // Advance clock to t=1800 — within 120s of expiry at 1900, should refresh
        clockRef.set(fixedClock(1800));

        RedisCredentials second = provider.resolveCredentials().block();
        assertNotNull(second);
        assertEquals("second-token", new String(second.getPassword()));

        verify(awsCredentialsProvider, times(2)).getCredentials();
    }

    @Test
    void shouldNotSupportStreaming() {
        AwsRedisCredentialsProvider provider = new AwsRedisCredentialsProvider("test-user", iamAuthTokenRequest, awsCredentialsProvider, fixedClock(1000));
        assertFalse(provider.supportsStreaming());
    }
}
