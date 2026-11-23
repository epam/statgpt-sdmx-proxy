package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.RegistryCoolingDownException;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.InMemoryCacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.singleflight.InProcessRequestCoalescer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import com.github.benmanes.caffeine.cache.Ticker;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RawStructureFetcherImpl} with a real {@link InProcessRequestCoalescer} and
 * {@link InMemoryCacheService} over a mocked {@link GenericRegistryAdapter} -- design 042.
 */
class RawStructureFetcherImplTest {

    private static final Duration CONDITION_TIMEOUT = Duration.ofSeconds(10);

    private final GenericRegistryAdapter adapter = mock(GenericRegistryAdapter.class);
    private final FakeTicker ticker = new FakeTicker();
    private final List<Thread> threads = new ArrayList<>();
    private CacheProperties properties;
    private InProcessRequestCoalescer coalescer;

    @BeforeEach
    void setUp() {
        properties = new CacheProperties();
        properties.getSingleFlight().setWaitTimeout(Duration.ofSeconds(30));
        properties.getSingleFlight().setRateLimitCooldown(Duration.ofSeconds(30));
        coalescer = new InProcessRequestCoalescer(properties, openTelemetry());
    }

    @AfterEach
    void inFlightRegistryIsEmpty() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(CONDITION_TIMEOUT.toMillis());
        }
        awaitCondition(() -> coalescerInFlight() == 0);
        assertThat(coalescerInFlight()).isZero();
    }

    @Test
    void fetch_fiftyConcurrentColdCacheCalls_oneUpstreamCall() throws Exception {
        CacheService cache = new InMemoryCacheService(properties);
        RawStructureFetcherImpl fetcher = fetcher(cache);
        TranslatedStructureQuery query = query("DSD_A");
        byte[] payload = bytes("dsd-a");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(adapter.getStructures(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            release.await(CONDITION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return new ByteArrayInputStream(payload);
        });

        List<CompletableFuture<Object>> outcomes = fetchConcurrently(fetcher, Collections.nCopies(50, query));
        awaitAllParked();
        release.countDown();

        Object first = outcomes.getFirst().get(10, TimeUnit.SECONDS);
        assertThat((byte[]) first).isEqualTo(payload);
        for (CompletableFuture<Object> outcome : outcomes) {
            assertThat(outcome.get(10, TimeUnit.SECONDS)).isSameAs(first);
        }
        assertThat(calls).hasValue(1);
        assertThat(cache.getRawStructures(key(query))).hasValueSatisfying(cached -> assertThat(cached).isSameAs(first));
    }

    @Test
    void fetch_tenStructuresFiveCallersEach_tenUpstreamCalls() throws Exception {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(adapter.getStructures(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            release.await(CONDITION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            TranslatedStructureQuery q = invocation.getArgument(0);
            return new ByteArrayInputStream(bytes(q.getStructure().id()));
        });
        List<TranslatedStructureQuery> queries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            queries.add(query("DSD_" + (i % 10)));
        }

        List<CompletableFuture<Object>> outcomes = fetchConcurrently(fetcher, queries);
        awaitAllParked();
        release.countDown();

        for (int i = 0; i < outcomes.size(); i++) {
            assertThat((byte[]) outcomes.get(i).get(10, TimeUnit.SECONDS)).isEqualTo(bytes("DSD_" + (i % 10)));
        }
        assertThat(calls).hasValue(10);
    }

    @Test
    void fetch_afterFlightCompletes_servedFromCache() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        when(adapter.getStructures(any())).thenAnswer(invocation -> new ByteArrayInputStream(bytes("dsd-a")));

        fetcher.fetch(query);
        byte[] second = fetcher.fetch(query);

        assertThat(second).isEqualTo(bytes("dsd-a"));
        verify(adapter, times(1)).getStructures(any());
    }

    @Test
    void fetch_storesRegistryBytesVerbatim() {
        CacheService cache = new InMemoryCacheService(properties);
        RawStructureFetcherImpl fetcher = fetcher(cache);
        TranslatedStructureQuery query = query("DSD_A");
        byte[] registryBytes = bytes("{\"data\":{\"dataStructures\":[]}}");
        when(adapter.getStructures(any())).thenReturn(new ByteArrayInputStream(registryBytes));

        byte[] fetched = fetcher.fetch(query);

        assertThat(fetched).isEqualTo(registryBytes);
        assertThat(cache.getRawStructures(key(query))).hasValueSatisfying(cached -> assertThat(cached).isEqualTo(registryBytes));
    }

    @Test
    void fetch_valueAppearsBeforeUpstream_recheckSkipsCall() {
        CacheService cache = mock(CacheService.class);
        byte[] landed = bytes("landed");
        when(cache.getRawStructures(anyString())).thenReturn(Optional.empty(), Optional.of(landed));
        RawStructureFetcherImpl fetcher = fetcher(cache);

        byte[] fetched = fetcher.fetch(query("DSD_A"));

        assertThat(fetched).isSameAs(landed);
        verify(adapter, never()).getStructures(any());
    }

    @Test
    void fetch_nullStream_returnsEmptyAndDoesNotCache() {
        CacheService cache = mock(CacheService.class);
        when(cache.getRawStructures(anyString())).thenReturn(Optional.empty());
        when(adapter.getStructures(any())).thenReturn(null);
        RawStructureFetcherImpl fetcher = fetcher(cache);

        byte[] fetched = fetcher.fetch(query("DSD_A"));

        assertThat(fetched).isEmpty();
        verify(cache, never()).putRawStructures(any(), any());
    }

    // --- design 044: negative cache and refresh ---

    @Test
    void fetch_emptyAnswer_servedFromNegativeCacheWithinTtl() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        when(adapter.getStructures(any())).thenReturn(null);

        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();
        awaitCondition(() -> coalescerInFlight() == 0);
        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();

        verify(adapter, times(1)).getStructures(any());
    }

    @Test
    void fetch_zeroByteBody_goesToNegativeCacheNotRawCache() {
        CacheService cache = mock(CacheService.class);
        when(cache.getRawStructures(anyString())).thenReturn(Optional.empty());
        when(adapter.getStructures(any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        RawStructureFetcherImpl fetcher = fetcher(cache);

        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();
        awaitCondition(() -> coalescerInFlight() == 0);
        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();

        verify(cache, never()).putRawStructures(any(), any());
        verify(adapter, times(1)).getStructures(any());
    }

    @Test
    void fetch_emptyAnswer_refetchedAfterTtl() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        when(adapter.getStructures(any())).thenReturn(null).thenReturn(new ByteArrayInputStream(bytes("dsd-a")));

        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();
        awaitCondition(() -> coalescerInFlight() == 0);
        ticker.advance(properties.getSingleFlight().getEmptyResultTtl().plusSeconds(1));

        assertThat(fetcher.fetch(query("DSD_A"))).isEqualTo(bytes("dsd-a"));
        verify(adapter, times(2)).getStructures(any());
    }

    @Test
    void onConfigurationChanged_clearsNegativeCache() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        when(adapter.getStructures(any())).thenReturn(null).thenReturn(new ByteArrayInputStream(bytes("dsd-a")));

        assertThat(fetcher.fetch(query("DSD_A"))).isEmpty();
        awaitCondition(() -> coalescerInFlight() == 0);
        fetcher.onConfigurationChanged();

        assertThat(fetcher.fetch(query("DSD_A"))).isEqualTo(bytes("dsd-a"));
    }

    @Test
    void refresh_bypassesCacheAndReplacesEntry() {
        CacheService cache = new InMemoryCacheService(properties);
        RawStructureFetcherImpl fetcher = fetcher(cache);
        TranslatedStructureQuery query = query("DSD_A");
        when(adapter.getStructures(any())).thenReturn(new ByteArrayInputStream(bytes("old"))).thenReturn(new ByteArrayInputStream(bytes("new")));
        fetcher.fetch(query);
        awaitCondition(() -> coalescerInFlight() == 0);

        assertThat(fetcher.refresh(query)).isEqualTo(bytes("new"));
        awaitCondition(() -> coalescerInFlight() == 0);

        assertThat(cache.getRawStructures(key(query)).orElseThrow()).isEqualTo(bytes("new"));
        verify(adapter, times(2)).getStructures(any());
    }

    @Test
    void refresh_respectsCooldown() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        FeignException tooManyRequests = feignException(429, Map.of());
        when(adapter.getStructures(any())).thenThrow(tooManyRequests);
        assertThatThrownBy(() -> fetcher.fetch(query)).isSameAs(tooManyRequests);

        assertThatThrownBy(() -> fetcher.refresh(query)).isInstanceOf(RegistryCoolingDownException.class);
        verify(adapter, times(1)).getStructures(any());
    }

    @Test
    void fetch_exhausted429_entersCooldown() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        FeignException tooManyRequests = feignException(429, Map.of());
        when(adapter.getStructures(any())).thenThrow(tooManyRequests).thenReturn(new ByteArrayInputStream(bytes("dsd-a")));

        assertThatThrownBy(() -> fetcher.fetch(query)).isSameAs(tooManyRequests);
        assertThatThrownBy(() -> fetcher.fetch(query)).isInstanceOf(RegistryCoolingDownException.class);
        verify(adapter, times(1)).getStructures(any());

        ticker.advance(Duration.ofSeconds(31));
        assertThat(fetcher.fetch(query)).isEqualTo(bytes("dsd-a"));
        verify(adapter, times(2)).getStructures(any());
    }

    @Test
    void fetch_retryable429WithRetryAfter_entersCooldown() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        // The default decoder turns a 429 that carries Retry-After (OECD sends 0) into a
        // RetryableException; it must still count as an exhausted rate limit.
        FeignException decoded = feignException(429, Map.of("Retry-After", List.of("0")));
        assertThat(decoded).isInstanceOf(RetryableException.class);
        when(adapter.getStructures(any())).thenThrow(decoded);

        assertThatThrownBy(() -> fetcher.fetch(query)).isSameAs(decoded);
        assertThatThrownBy(() -> fetcher.fetch(query)).isInstanceOf(RegistryCoolingDownException.class);
        verify(adapter, times(1)).getStructures(any());
    }

    @Test
    void fetch_upstream500_noCooldown() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        FeignException serverError = feignException(500, Map.of());
        when(adapter.getStructures(any())).thenThrow(serverError).thenReturn(new ByteArrayInputStream(bytes("dsd-a")));

        assertThatThrownBy(() -> fetcher.fetch(query)).isSameAs(serverError);
        assertThat(fetcher.fetch(query)).isEqualTo(bytes("dsd-a"));
        verify(adapter, times(2)).getStructures(any());
    }

    @Test
    void fetch_upstreamFailure_inFlightRemovedAndNextCallRetries() {
        RawStructureFetcherImpl fetcher = fetcher(new InMemoryCacheService(properties));
        TranslatedStructureQuery query = query("DSD_A");
        RuntimeException networkDown = new IllegalStateException("connection reset");
        when(adapter.getStructures(any())).thenThrow(networkDown).thenReturn(new ByteArrayInputStream(bytes("dsd-a")));

        assertThatThrownBy(() -> fetcher.fetch(query)).isSameAs(networkDown);
        awaitCondition(() -> coalescerInFlight() == 0);
        assertThat(fetcher.fetch(query)).isEqualTo(bytes("dsd-a"));
        verify(adapter, times(2)).getStructures(any());
    }

    // --- helpers ---

    private RawStructureFetcherImpl fetcher(CacheService cache) {
        return new RawStructureFetcherImpl(adapter, cache, coalescer, new Sdmx21QueryNormalizerImpl(), properties, openTelemetry(), ticker);
    }

    private int coalescerInFlight() {
        return coalescer.inFlightCount();
    }

    private List<CompletableFuture<Object>> fetchConcurrently(RawStructureFetcherImpl fetcher, List<TranslatedStructureQuery> queries) {
        List<CompletableFuture<Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            TranslatedStructureQuery query = queries.get(i);
            CompletableFuture<Object> outcome = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    outcome.complete(fetcher.fetch(query));
                } catch (Throwable t) {
                    outcome.complete(t);
                }
            }, "fetch-caller-" + i);
            threads.add(thread);
            outcomes.add(outcome);
            thread.start();
        }
        return outcomes;
    }

    private void awaitAllParked() {
        awaitCondition(() -> threads.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING));
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + CONDITION_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + CONDITION_TIMEOUT);
            }
            Thread.onSpinWait();
        }
    }

    private static String key(TranslatedStructureQuery query) {
        return CacheKeyGenerator.generateStructureKey(query, new Sdmx21QueryNormalizerImpl());
    }

    private static TranslatedStructureQuery query(String dsdId) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("OECD");
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        structureEndpoint.setUrl("https://registry.example.com/rest");
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .structure(new Structure("datastructure", "OECD.ENV", dsdId, "1.0"))
                .references("none")
                .detail("full")
                .registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private static FeignException feignException(int status, Map<String, Collection<String>> headers) {
        Request request = Request.create(Request.HttpMethod.GET, "https://registry.example.com/rest/datastructure", Collections.emptyMap(), null, new RequestTemplate());
        Response response = Response.builder().status(status).reason("reason").request(request).headers(headers).body("{}".getBytes(StandardCharsets.UTF_8)).build();
        return (FeignException) new ErrorDecoder.Default().decode("Sdmx21StructureClient#getStructures", response);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OpenTelemetry> openTelemetry() {
        ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(OpenTelemetry.noop());
        return provider;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
