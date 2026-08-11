package com.epam.sdmxproxy.registry.api.http;

import feign.Client;
import feign.Request;
import feign.Response;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feign {@link Client} decorator that pins {@code Accept-Encoding} to {@code identity}.
 * <p>
 * OkHttp adds {@code Accept-Encoding: gzip} transparently and decompresses only when the response
 * carries {@code Content-Encoding}. A registry that compresses without that header hands the raw
 * gzip stream to the SDMX readers, which fail with a null {@code SdmxBeans} or
 * {@code SdmxSyntaxException: 800}. Setting the header on the {@link Request} suppresses OkHttp's
 * transparent negotiation, so the registry answers uncompressed.
 * <p>
 * Sits below the 429 retry decorator so a replayed request is re-decorated on every attempt.
 */
@RequiredArgsConstructor
public class IdentityEncodingClient implements Client {

    private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
    private static final String IDENTITY = "identity";

    private final Client delegate;

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        return delegate.execute(withIdentityEncoding(request), options);
    }

    /**
     * Rebuilds the request with exactly one {@code Accept-Encoding} header. Every existing spelling
     * is removed first: HTTP header names are case-insensitive but Feign's header map is a plain
     * {@link Map}, so a lowercase entry would otherwise survive an uppercase put.
     */
    private Request withIdentityEncoding(Request request) {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        request.headers().forEach((name, values) -> {
            if (!ACCEPT_ENCODING_HEADER.equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        List<String> identity = new ArrayList<>();
        identity.add(IDENTITY);
        headers.put(ACCEPT_ENCODING_HEADER, identity);
        return Request.create(request.httpMethod(), request.url(), headers, request.body(), request.charset(), request.requestTemplate());
    }
}
