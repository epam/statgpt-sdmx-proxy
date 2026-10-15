package com.epam.sdmxproxy.registry.api.http;

import feign.Client;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Design 041 / P1. Eurostat gzips whenever the request asks for gzip and sets no
 * {@code Content-Encoding}, so the readers get a raw gzip stream. The decorator pins the header.
 */
class IdentityEncodingClientTest {

    private CapturingClient delegate;
    private IdentityEncodingClient sut;

    @BeforeEach
    void setUp() {
        delegate = new CapturingClient();
        sut = new IdentityEncodingClient(delegate);
    }

    @Test
    void shouldSetAcceptEncodingToIdentity() throws IOException {
        sut.execute(request(new LinkedHashMap<>()), new Request.Options());

        Collection<String> values = delegate.captured.headers().get("Accept-Encoding");
        assertNotNull(values, "the decorator must add Accept-Encoding");
        assertIterableEquals(List.of("identity"), values);
    }

    @Test
    void shouldReplaceAnExistingGzipHeader() throws IOException {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("accept-encoding", List.of("gzip"));
        headers.put("Accept-Encoding", List.of("gzip, deflate"));

        sut.execute(request(headers), new Request.Options());

        long acceptEncodingHeaders = delegate.captured.headers().keySet().stream().filter(name -> name.equalsIgnoreCase("Accept-Encoding")).count();
        assertEquals(1L, acceptEncodingHeaders, "exactly one Accept-Encoding must survive, in any casing");
        assertIterableEquals(List.of("identity"), delegate.captured.headers().get("Accept-Encoding"));
    }

    @Test
    void shouldPreserveOtherHeadersAndUrl() throws IOException {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("Accept", List.of("application/vnd.sdmx.structure+xml;version=3.0.0"));

        sut.execute(request(headers), new Request.Options());

        assertIterableEquals(List.of("application/vnd.sdmx.structure+xml;version=3.0.0"), delegate.captured.headers().get("Accept"));
        assertEquals("https://example.com/structure/dataflow/ESTAT/TPS00001/1.0", delegate.captured.url());
        assertEquals(Request.HttpMethod.GET, delegate.captured.httpMethod());
    }

    private Request request(Map<String, Collection<String>> headers) {
        return Request.create(Request.HttpMethod.GET, "https://example.com/structure/dataflow/ESTAT/TPS00001/1.0", headers, null, StandardCharsets.UTF_8, new RequestTemplate());
    }

    private static final class CapturingClient implements Client {
        private Request captured;

        @Override
        public Response execute(Request request, Request.Options options) {
            this.captured = request;
            return Response.builder().status(200).request(request).build();
        }
    }
}
