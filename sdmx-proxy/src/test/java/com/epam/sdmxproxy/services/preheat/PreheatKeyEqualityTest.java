package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.PreheatConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.registry.api.http.Sleeper;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.adapter.RawStructureFetcher;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.cache.config.CacheProperties;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import io.opentelemetry.api.OpenTelemetry;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 044: the keys preheat writes equal the keys of StatGPT's live requests, and a split ready
 * response equals what the live conversion path produces for the direct registry answer.
 * <p>
 * Runs against the real translator, router, conversion service and in-memory cache with the
 * bundled configuration (OECD: SDMX 2.1 only, no bypass, no fixtures, {@code preheatEnabled}).
 * The registry is never called: the OECD answers for {@code Q1} and {@code Q2} are put into the raw
 * cache under the keys preheat computes, and the fetcher handed to preheat is a mock.
 */
@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
class PreheatKeyEqualityTest {

    private static final String XML_21 = "application/vnd.sdmx.structure+xml;version=2.1";
    private static final String AGENCY = "OECD.STI.DEP";
    private static final String DATAFLOW_ID = "DSD_TQ_SURVEY@DF_TRUTH_QUEST";
    private static final String DSD_URN = "urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=OECD.STI.DEP:DSD_TQ_SURVEY(1.0)";

    @Autowired
    private ProxyConfigurationProvider configurationProvider;
    @Autowired
    private QueryTranslator queryTranslator;
    @Autowired
    private AdapterRouter adapterRouter;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private DescendantsSplitter descendantsSplitter;
    @Autowired
    private Sdmx21QueryNormalizer sdmx21QueryNormalizer;
    @Autowired
    private CacheProperties cacheProperties;
    @Autowired
    private StreamingStructureConversionService conversionService;

    private final RawStructureFetcher preheatFetcher = mock(RawStructureFetcher.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private PreheatService preheat;

    @BeforeEach
    void setUp() {
        cacheService.invalidateAll();
        PreheatProperties properties = new PreheatProperties();
        properties.setEnabled(true);
        ProxyConfigurationProvider truthQuestOnly = () -> withPreheat(configurationProvider.getConfiguration());
        preheat = new PreheatService(truthQuestOnly, queryTranslator, adapterRouter, preheatFetcher, cacheService, descendantsSplitter, sdmx21QueryNormalizer, properties, cacheProperties, noWaitSleeper(), openTelemetry(), executor, System::nanoTime);

        // The availability controller's descendants call shape: Accept and source URN null.
        TranslatedStructureQuery flowQuery = queryTranslator.translateStructureQuery("dataflow", AGENCY, DATAFLOW_ID, "latest", "datastructure", "full", XML_21, null);
        TranslatedStructureQuery descendantsQuery = queryTranslator.translateStructureQuery("dataflow", AGENCY, DATAFLOW_ID, "1.0", "descendants", "full", null, null);
        cacheService.putRawStructures(CacheKeyGenerator.generateStructureKey(flowQuery, sdmx21QueryNormalizer), fixture("oecd_truth_quest_datastructure.xml"));
        cacheService.putRawStructures(CacheKeyGenerator.generateStructureKey(descendantsQuery, sdmx21QueryNormalizer), fixture("oecd_truth_quest_descendants.xml"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        cacheService.invalidateAll();
    }

    @Test
    void shouldServeStatGptCodelistRequestFromSplitWithoutRegistryCall() {
        preheat.run("test");

        TranslatedStructureQuery live = statGptPointRequest("codelist", "CL_MEASURE_TQ");
        assertThat(cacheService.getReadyResponse(readyKey(live))).isPresent();
        assertThat(cacheService.getRawStructuresTimeToLive(CacheKeyGenerator.generateStructureKey(live, sdmx21QueryNormalizer))).isEmpty();
        verifyNoInteractions(preheatFetcher);
    }

    @Test
    void shouldWarmStatGptDataflowRequest() {
        preheat.run("test");

        TranslatedStructureQuery live = queryTranslator.translateStructureQuery("dataflow", AGENCY, DATAFLOW_ID, "latest", "datastructure", "full", XML_21, null);
        assertThat(cacheService.getReadyResponse(readyKey(live))).isPresent();
    }

    @Test
    void codelistShouldMatchLiveConversion() {
        preheat.run("test");

        byte[] served = serve(statGptPointRequest("codelist", "CL_MEASURE_TQ"));
        byte[] live = convertDirect("oecd_cl_measure_tq.xml");

        assertThat(withoutHeader(served)).isEqualTo(withoutHeader(live));
    }

    @Test
    void conceptSchemeShouldMatchLiveConversion() {
        preheat.run("test");

        byte[] served = serve(statGptPointRequest("conceptscheme", "CS_TQ_SURVEY"));
        byte[] live = convertDirect("oecd_cs_tq_survey.xml");

        assertThat(withoutHeader(served)).isEqualTo(withoutHeader(live));
    }

    @Test
    void shouldSplitEveryConceptSchemeAndCodelist() {
        preheat.run("test");

        for (String codelist : List.of("CL_AREA_TOOLKIT", "CL_BREAKDOWN_TQ", "CL_MEASURE_TQ", "CL_OBS_STATUS_TOOLKIT", "CL_UNIT_MEASURE_TQ")) {
            assertThat(cacheService.getReadyResponse(readyKey(statGptPointRequest("codelist", codelist)))).as(codelist).isPresent();
        }
        assertThat(cacheService.getReadyResponse(readyKey(statGptPointRequest("conceptscheme", "CS_TQ_SURVEY")))).isPresent();
    }

    /**
     * The controller's argument shape for StatGPT's point request: references absent (null),
     * detail defaulted to {@code full}, the XML 2.1 Accept, and the DSD URN header.
     */
    private TranslatedStructureQuery statGptPointRequest(String type, String id) {
        return queryTranslator.translateStructureQuery(type, AGENCY, id, "1.0", null, "full", XML_21, DSD_URN);
    }

    private String readyKey(TranslatedStructureQuery query) {
        return CacheKeyGenerator.generateResponseKey(query, query.getContentType(), Collections.emptyMap(), sdmx21QueryNormalizer);
    }

    @SneakyThrows
    private byte[] serve(TranslatedStructureQuery query) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        adapterRouter.getStructures(query).writeTo(out);
        return out.toByteArray();
    }

    private byte[] convertDirect(String fixture) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.convert(new ByteArrayInputStream(fixture(fixture)), out, SdmxFormat.XML_STRUCTURE_2_1, MediaType.parseMediaType(XML_21));
        return out.toByteArray();
    }

    private static String withoutHeader(byte[] message) {
        return new String(message, StandardCharsets.UTF_8).replaceAll("(?s)<([A-Za-z0-9]+:)?Header>.*?</([A-Za-z0-9]+:)?Header>", "");
    }

    private static ProxyConfiguration withPreheat(ProxyConfiguration source) {
        ProxyConfiguration copy = new ProxyConfiguration();
        copy.setConfigs(source.getConfigs());
        copy.setAgencies(source.getAgencies());
        copy.setStructureFanOutEnabled(source.isStructureFanOutEnabled());
        PreheatConfiguration group = new PreheatConfiguration();
        group.setMediaType(XML_21);
        group.setDataflows(List.of(AGENCY + ":" + DATAFLOW_ID + "(latest)"));
        copy.setPreheat(List.of(group));
        return copy;
    }

    @SneakyThrows
    private static byte[] fixture(String name) {
        try (InputStream in = PreheatKeyEqualityTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    private static Sleeper noWaitSleeper() {
        return millis -> true;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OpenTelemetry> openTelemetry() {
        ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(OpenTelemetry.noop());
        return provider;
    }
}
