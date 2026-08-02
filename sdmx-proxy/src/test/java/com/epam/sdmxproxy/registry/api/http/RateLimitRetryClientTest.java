package com.epam.sdmxproxy.registry.api.http;

import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitRetryClientTest {

    private static final RateLimitRetrySettings SETTINGS = new RateLimitRetrySettings(4, 5000L, 2.0, 60000L, 1_000_000L);

    private List<Long> sleeps;
    private Sleeper recordingSleeper;

    @BeforeEach
    void setUp() {
        sleeps = new ArrayList<>();
        recordingSleeper = millis -> {
            sleeps.add(millis);
            return true;
        };
    }

    @Test
    void shouldRetryOn429AndReturnFirstNon429Response() throws IOException {
        CountingClient delegate = new CountingClient(response(429), response(429), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        Response response = client.execute(request(), options());

        assertThat(response.status()).isEqualTo(200);
        assertThat(delegate.calls).isEqualTo(3);
    }

    @Test
    void shouldStopAfterMaxAttempts() throws IOException {
        CountingClient delegate = new CountingClient(response(429), response(429), response(429), response(429), response(429));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        Response response = client.execute(request(), options());

        assertThat(response.status()).isEqualTo(429);
        assertThat(delegate.calls).isEqualTo(SETTINGS.maxAttempts());
    }

    @Test
    void shouldNotRetryNon429() throws IOException {
        CountingClient delegate = new CountingClient(response(500), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        Response response = client.execute(request(), options());

        assertThat(response.status()).isEqualTo(500);
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void shouldUseExponentialBackoff() throws IOException {
        CountingClient delegate = new CountingClient(response(429), response(429), response(429), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        client.execute(request(), options());

        assertThat(sleeps).containsExactly(5000L, 10000L, 20000L);
    }

    @Test
    void shouldIgnoreZeroRetryAfterAndUseBackoff() throws IOException {
        // Cloudflare-fronted registries return a literal `Retry-After: 0` alongside the 429.
        CountingClient delegate = new CountingClient(response(429, "0"), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        client.execute(request(), options());

        assertThat(sleeps).containsExactly(5000L);
    }

    @Test
    void shouldHonourLargerRetryAfter() throws IOException {
        CountingClient delegate = new CountingClient(response(429, "30"), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        client.execute(request(), options());

        assertThat(sleeps).containsExactly(30000L);
    }

    @Test
    void shouldClampRetryAfterToMaxInterval() throws IOException {
        CountingClient delegate = new CountingClient(response(429, "3600"), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        client.execute(request(), options());

        assertThat(sleeps).containsExactly(SETTINGS.maxIntervalMillis());
    }

    @Test
    void shouldIgnoreUnparseableRetryAfter() throws IOException {
        CountingClient delegate = new CountingClient(response(429, "Wed, 21 Oct 2026 07:28:00 GMT"), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        client.execute(request(), options());

        assertThat(sleeps).containsExactly(5000L);
    }

    @Test
    void shouldStopWhenTotalWaitBudgetExhausted() throws IOException {
        RateLimitRetrySettings tightBudget = new RateLimitRetrySettings(4, 5000L, 2.0, 60000L, 12000L);
        CountingClient delegate = new CountingClient(response(429), response(429), response(429), response(429));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, tightBudget, recordingSleeper);

        Response response = client.execute(request(), options());

        // 5000 fits, 5000+10000 does not, so the second retry is refused.
        assertThat(sleeps).containsExactly(5000L);
        assertThat(delegate.calls).isEqualTo(2);
        assertThat(response.status()).isEqualTo(429);
    }

    @Test
    void shouldCloseDiscarded429ResponsesAndLeaveFinalOneOpen() throws IOException {
        // doNotCloseAfterDecode() is set on the Feign builder, so nothing else closes a discarded
        // 429 -- leaking it would exhaust the OkHttp connection pool.
        RecordingBody discardedBody = new RecordingBody();
        RecordingBody finalBody = new RecordingBody();
        CountingClient delegate = new CountingClient(responseWithBody(429, discardedBody), responseWithBody(200, finalBody));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, recordingSleeper);

        Response response = client.execute(request(), options());

        assertThat(discardedBody.closed).as("discarded 429 body must be closed").isTrue();
        assertThat(finalBody.closed).as("final body must stay open for the decoder").isFalse();
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void shouldAbortRetryingWhenInterrupted() throws IOException {
        CountingClient delegate = new CountingClient(response(429), response(429), response(200));
        RateLimitRetryClient client = new RateLimitRetryClient(delegate, SETTINGS, millis -> false);

        client.execute(request(), options());

        assertThat(delegate.calls).isEqualTo(2);
    }

    private static Response responseWithBody(int status, Response.Body body) {
        return Response.builder()
                .status(status)
                .reason("stub")
                .request(request())
                .headers(Collections.emptyMap())
                .body(body)
                .build();
    }

    private static final class RecordingBody implements Response.Body {

        private boolean closed;

        @Override
        public Integer length() {
            return 0;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public java.io.InputStream asInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override
        public java.io.Reader asReader(java.nio.charset.Charset charset) {
            return new java.io.InputStreamReader(asInputStream(), charset);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "https://registry.example/data", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
    }

    private static Request.Options options() {
        return new Request.Options();
    }

    private static Response response(int status) {
        return response(status, null);
    }

    private static Response response(int status, String retryAfter) {
        Map<String, Collection<String>> headers = retryAfter == null ? Collections.emptyMap() : Map.of("Retry-After", List.of(retryAfter));
        return Response.builder()
                .status(status)
                .reason("stub")
                .request(request())
                .headers(headers)
                .body(new byte[]{1, 2, 3})
                .build();
    }

    private static final class CountingClient implements Client {

        private final Response[] responses;
        private int calls;

        private CountingClient(Response... responses) {
            this.responses = responses;
        }

        @Override
        public Response execute(Request request, Request.Options options) {
            Response response = responses[Math.min(calls, responses.length - 1)];
            calls++;
            return response;
        }
    }
}
