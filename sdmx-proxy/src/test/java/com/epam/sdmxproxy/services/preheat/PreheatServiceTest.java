package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.PreheatConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.AgencyRoutingException;
import com.epam.sdmxproxy.exception.RegistryCoolingDownException;
import com.epam.sdmxproxy.registry.api.http.Sleeper;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationChangedEvent;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.adapter.RawStructureFetcher;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import io.opentelemetry.api.OpenTelemetry;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.ISdmxVersion;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptSchemeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PreheatService} run logic over mocked translator, router, fetcher and cache -- design 044.
 */
class PreheatServiceTest {

    private static final String XML_21 = "application/vnd.sdmx.structure+xml;version=2.1";
    private static final String AGENCY = "OECD.STI.DEP";
    private static final String FLOW_ID = "DSD_TQ_SURVEY@DF_TRUTH_QUEST";
    private static final String DSD_URN = "urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=OECD.STI.DEP:DSD_TQ_SURVEY(1.0)";

    private final Sdmx21QueryNormalizer normalizer = new Sdmx21QueryNormalizerImpl();
    private final QueryTranslator translator = mock(QueryTranslator.class);
    private final AdapterRouter router = mock(AdapterRouter.class);
    private final RawStructureFetcher fetcher = mock(RawStructureFetcher.class);
    private final CacheService cache = mock(CacheService.class);
    private final DescendantsSplitter splitter = mock(DescendantsSplitter.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final Map<String, Duration> rawTtl = new HashMap<>();
    private final Map<String, Duration> readyTtl = new HashMap<>();

    private PreheatProperties properties;
    private ProxyConfiguration configuration;
    private RegistryConfiguration registry;
    private ProxyConfigurationProvider provider;
    private SdmxBeans closure;
    private PreheatService preheat;

    @BeforeEach
    void setUp() {
        properties = new PreheatProperties();
        properties.setEnabled(true);
        registry = registry("OECD", true, false);
        configuration = configuration(List.of(AGENCY + ":" + FLOW_ID + "(latest)"));
        provider = () -> configuration;

        when(translator.translateStructureQuery(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any())).thenAnswer(invocation -> query(registry, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(6)));
        when(router.getStructures(any())).thenReturn(out -> { });
        SdmxBeans flowBeans = mock(SdmxBeans.class);
        DataflowBean flow = dataflow();
        when(flowBeans.getDataflows()).thenReturn(Set.of(flow));
        closure = closure(List.of(codelist("CL_A"), codelist("CL_B")), List.of(scheme("CS_A")));
        when(router.getSdmxBeans(any())).thenAnswer(invocation -> "datastructure".equals(invocation.getArgument(0, TranslatedStructureQuery.class).getReferences()) ? flowBeans : closure);
        when(cache.getRawStructuresTimeToLive(anyString())).thenAnswer(invocation -> Optional.ofNullable(rawTtl.get(invocation.getArgument(0, String.class))));
        when(cache.getReadyResponseTimeToLive(anyString())).thenAnswer(invocation -> Optional.ofNullable(readyTtl.get(invocation.getArgument(0, String.class))));
        when(splitter.readyResponseKey(any())).thenAnswer(invocation -> "ready:" + invocation.getArgument(0, TranslatedStructureQuery.class).getStructure());
        preheat = service(provider);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void shouldWarmFlowDescendantsAndSplitWithStatGptArgumentShapes() {
        preheat.run("test");

        verify(translator).translateStructureQuery("dataflow", AGENCY, FLOW_ID, "latest", "datastructure", "full", XML_21, null);
        verify(translator).translateStructureQuery("dataflow", AGENCY, FLOW_ID, "1.0", "descendants", "full", null, null);
        verify(translator).translateStructureQuery("codelist", AGENCY, "CL_A", "1.0", null, "full", XML_21, DSD_URN);
        verify(translator).translateStructureQuery("conceptscheme", AGENCY, "CS_A", "1.0", null, "full", XML_21, DSD_URN);
        verify(fetcher, times(2)).fetch(any());
        verify(fetcher, never()).refresh(any());
        verify(splitter, times(3)).store(any(), any(), any());
    }

    @Test
    void shouldNotCallRegistryForPointArtefacts() {
        preheat.run("test");

        ArgumentCaptor<TranslatedStructureQuery> fetched = ArgumentCaptor.forClass(TranslatedStructureQuery.class);
        verify(fetcher, times(2)).fetch(fetched.capture());
        assertThat(fetched.getAllValues()).extracting(query -> query.getStructure().type()).containsOnly("dataflow");
    }

    @Test
    void shouldSkipRegistryWithoutPreheatEnabled() {
        registry = registry("OECD", false, false);

        preheat.run("test");

        verifyNoInteractions(fetcher, splitter);
        verify(router, never()).getSdmxBeans(any());
    }

    @Test
    void shouldDoNothingWhenDisabled() {
        properties.setEnabled(false);

        preheat.run("test");

        verifyNoInteractions(translator, fetcher, splitter);
    }

    @Test
    void shouldRefreshRawEntriesNearExpiry() {
        rawTtl.put(flowKey(), Duration.ofHours(1));
        rawTtl.put(descendantsKey(), Duration.ofHours(1));

        preheat.run("test");

        verify(fetcher, times(2)).refresh(any());
        verify(fetcher, never()).fetch(any());
    }

    @Test
    void shouldNotTouchFreshRawEntries() {
        rawTtl.put(flowKey(), Duration.ofHours(23));
        rawTtl.put(descendantsKey(), Duration.ofHours(23));

        preheat.run("test");

        verifyNoInteractions(fetcher);
    }

    @Test
    void shouldDeferRefreshesOverBudget() {
        properties.setMaxRefreshesPerRun(1);
        rawTtl.put(flowKey(), Duration.ofHours(1));
        rawTtl.put(descendantsKey(), Duration.ofHours(1));

        preheat.run("test");

        verify(fetcher, times(1)).refresh(any());
    }

    @Test
    void shouldFetchMissingEntriesBeyondBudget() {
        properties.setMaxRefreshesPerRun(1);
        rawTtl.put(flowKey(), Duration.ofHours(1));

        preheat.run("test");

        verify(fetcher, times(1)).refresh(any());
        verify(fetcher, times(1)).fetch(any());
    }

    @Test
    void shouldRebuildSplitEntriesNearExpiryOnly() {
        readyTtl.put("ready:" + new Structure("codelist", AGENCY, "CL_A", "1.0"), Duration.ofHours(1));
        readyTtl.put("ready:" + new Structure("codelist", AGENCY, "CL_B", "1.0"), Duration.ofHours(5));
        readyTtl.put("ready:" + new Structure("conceptscheme", AGENCY, "CS_A", "1.0"), Duration.ofHours(5));

        preheat.run("test");

        verify(splitter, times(1)).store(argThat(query -> "CL_A".equals(query.getStructure().id())), any(), any());
        verify(splitter, times(1)).store(any(), any(), any());
    }

    @Test
    void shouldStopWithinDataflowOnConfigurationChange() {
        doAnswer(invocation -> {
            preheat.onConfigurationChanged(new ProxyConfigurationChangedEvent("test", true));
            return null;
        }).when(splitter).store(any(), any(), any());

        preheat.run("test");

        verify(splitter, times(1)).store(any(), any(), any());
    }

    @Test
    void shouldStopOnInterrupt() {
        configuration = configuration(List.of(AGENCY + ":" + FLOW_ID + "(latest)", AGENCY + ":OTHER_FLOW(latest)"));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(splitter).store(any(), any(), any());

        preheat.run("test");

        verify(splitter, times(1)).store(any(), any(), any());
        verify(translator, never()).translateStructureQuery(eq("dataflow"), eq(AGENCY), eq("OTHER_FLOW"), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void shouldRetryAfterRateLimit() {
        when(fetcher.fetch(any())).thenThrow(new RegistryCoolingDownException("cooling")).thenReturn(new byte[0]);

        preheat.run("test");

        assertThat(sleeps).contains(properties.getRateLimitBackoff().toMillis());
        verify(splitter, times(3)).store(any(), any(), any());
    }

    @Test
    void shouldGiveUpAfterMaxAttemptsAndContinue() {
        configuration = configuration(List.of(AGENCY + ":" + FLOW_ID + "(latest)", AGENCY + ":OTHER_FLOW(latest)"));
        when(fetcher.fetch(argThat(query -> query != null && FLOW_ID.equals(query.getStructure().id())))).thenThrow(new RegistryCoolingDownException("cooling"));

        preheat.run("test");

        verify(fetcher, times(properties.getMaxAttemptsPerDataflow())).fetch(argThat(query -> query != null && FLOW_ID.equals(query.getStructure().id())));
        verify(translator).translateStructureQuery("dataflow", AGENCY, "OTHER_FLOW", "latest", "datastructure", "full", XML_21, null);
    }

    @Test
    void shouldContinueAfterFailedDataflow() {
        configuration = configuration(List.of("UNKNOWN:DF_X(latest)", AGENCY + ":" + FLOW_ID + "(latest)"));
        when(translator.translateStructureQuery(eq("dataflow"), eq("UNKNOWN"), anyString(), anyString(), any(), anyString(), any(), any())).thenThrow(new AgencyRoutingException("no registry for UNKNOWN"));

        preheat.run("test");

        verify(splitter, times(3)).store(any(), any(), any());
    }

    @Test
    void shouldSurviveThrowingConfigurationLookup() {
        PreheatService throwing = service(() -> {
            throw new UnsupportedOperationException("source not supported");
        });

        assertThatCode(() -> throwing.run("periodic")).doesNotThrowAnyException();
    }

    @Test
    void shouldTreatNullGroupsAndListsAsEmpty() {
        List<PreheatConfiguration> groups = new ArrayList<>();
        groups.add(null);
        groups.add(new PreheatConfiguration());
        PreheatConfiguration noDataflows = new PreheatConfiguration();
        noDataflows.setMediaType(XML_21);
        groups.add(noDataflows);
        configuration.setPreheat(groups);

        assertThatCode(() -> preheat.run("test")).doesNotThrowAnyException();
        verifyNoInteractions(fetcher, splitter);
    }

    @Test
    void shouldSkipSplitWhenMediaTypeIsBypassed() {
        registry = registry("OECD", true, true);

        preheat.run("test");

        verify(splitter, never()).store(any(), any(), any());
    }

    @Test
    void shouldSkipSplitWhenPointQueryUsesAnotherEndpoint() {
        RegistryConfiguration other = registry("OTHER", true, false);
        when(translator.translateStructureQuery(eq("codelist"), anyString(), anyString(), anyString(), isNull(), anyString(), any(), any())).thenAnswer(invocation -> query(other, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3), null, invocation.getArgument(6)));

        preheat.run("test");

        verify(splitter, times(1)).store(argThat(query -> "CS_A".equals(query.getStructure().id())), any(), any());
        verify(splitter, times(1)).store(any(), any(), any());
    }

    @Test
    void shouldFailDataflowWithEmptyDescendants() {
        closure = closure(List.of(), List.of());

        assertThatCode(() -> preheat.run("test")).doesNotThrowAnyException();
        verify(splitter, never()).store(any(), any(), any());
    }

    @Test
    void refreshThresholdShouldSpreadKeys() {
        Set<Duration> thresholds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String key = "structure:codelist:OECD:SDMX_2_1:XML_STRUCTURE_2_1:hash:OECD.STI.DEP:CL_" + i + ":1.0::full";
            Duration threshold = preheat.refreshThreshold(key);
            assertThat(threshold).isBetween(properties.getRefreshAhead(), properties.getRefreshAhead().plus(properties.getRefreshSpread()));
            assertThat(preheat.refreshThreshold(key)).isEqualTo(threshold);
            thresholds.add(threshold);
        }
        assertThat(thresholds).hasSizeGreaterThan(10);
    }

    @Test
    void onConfigurationChangedShouldScheduleRun() {
        AtomicInteger scheduled = new AtomicInteger();
        when(executor.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(invocation -> {
            scheduled.incrementAndGet();
            return null;
        });

        preheat.onConfigurationChanged(new ProxyConfigurationChangedEvent("test", false));

        assertThat(scheduled).hasValue(1);
    }

    // --- helpers ---

    private PreheatService service(ProxyConfigurationProvider configurationProvider) {
        Sleeper sleeper = millis -> {
            sleeps.add(millis);
            return !Thread.currentThread().isInterrupted();
        };
        return new PreheatService(configurationProvider, translator, router, fetcher, cache, splitter, normalizer, properties, new CacheProperties(), sleeper, openTelemetry(), executor, System::nanoTime);
    }

    private String flowKey() {
        return CacheKeyGenerator.generateStructureKey(query(registry, "dataflow", AGENCY, FLOW_ID, "latest", "datastructure", XML_21), normalizer);
    }

    private String descendantsKey() {
        return CacheKeyGenerator.generateStructureKey(query(registry, "dataflow", AGENCY, FLOW_ID, "1.0", "descendants", null), normalizer);
    }

    private static TranslatedStructureQuery query(RegistryConfiguration registry, String type, String agency, String id, String version, String references, String mediaType) {
        return TranslatedStructureQuery.builder().registryConfiguration(registry).versionConfiguration(registry.getVersionConfiguration(SdmxVersion.SDMX_2_1)).structure(new Structure(type, agency, id, version)).references(references).detail("full").contentType(mediaType == null ? MediaType.APPLICATION_JSON : MediaType.parseMediaType(mediaType)).registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1).build();
    }

    private static RegistryConfiguration registry(String name, boolean preheatEnabled, boolean bypassEnabled) {
        StructureEndpointConfiguration structure = new StructureEndpointConfiguration();
        structure.setUrl("https://" + name.toLowerCase() + ".example.com/rest/");
        structure.setSupportedFormats(List.of(SdmxFormat.XML_STRUCTURE_2_1));
        structure.setDefaultFormat(SdmxFormat.XML_STRUCTURE_2_1);
        structure.setBypassEnabled(bypassEnabled);
        structure.setPreheatEnabled(preheatEnabled);
        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_2_1);
        version.setStructureEndpointConfig(structure);
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(name);
        registry.setVersions(Map.of(SdmxVersion.SDMX_2_1, version));
        return registry;
    }

    private static ProxyConfiguration configuration(List<String> dataflows) {
        PreheatConfiguration group = new PreheatConfiguration();
        group.setMediaType(XML_21);
        group.setDataflows(dataflows);
        ProxyConfiguration configuration = new ProxyConfiguration();
        configuration.setPreheat(List.of(group));
        return configuration;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataflowBean dataflow() {
        DataflowBean flow = mock(DataflowBean.class);
        ISdmxVersion version = version();
        when(flow.getVersion()).thenReturn(version);
        ICrossReferenceBean dsdRef = mock(ICrossReferenceBean.class);
        when(dsdRef.getTargetUrn()).thenReturn(DSD_URN);
        when(flow.getDataStructureRef()).thenReturn(dsdRef);
        return flow;
    }

    private static SdmxBeans closure(List<CodelistBean> codelists, List<ConceptSchemeBean> schemes) {
        SdmxBeans beans = mock(SdmxBeans.class);
        when(beans.getCodelists()).thenReturn(new HashSet<>(codelists));
        when(beans.getConceptSchemes()).thenReturn(new HashSet<>(schemes));
        return beans;
    }

    private static CodelistBean codelist(String id) {
        CodelistBean codelist = mock(CodelistBean.class);
        ISdmxVersion version = version();
        when(codelist.getAgencyId()).thenReturn(AGENCY);
        when(codelist.getId()).thenReturn(id);
        when(codelist.getVersion()).thenReturn(version);
        return codelist;
    }

    private static ConceptSchemeBean scheme(String id) {
        ConceptSchemeBean scheme = mock(ConceptSchemeBean.class);
        ISdmxVersion version = version();
        when(scheme.getAgencyId()).thenReturn(AGENCY);
        when(scheme.getId()).thenReturn(id);
        when(scheme.getVersion()).thenReturn(version);
        return scheme;
    }

    private static ISdmxVersion version() {
        ISdmxVersion version = mock(ISdmxVersion.class);
        when(version.toString()).thenReturn("1.0");
        return version;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OpenTelemetry> openTelemetry() {
        ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(OpenTelemetry.noop());
        return provider;
    }
}
