package com.epam.sdmxproxy.services.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityAsyncRetryConfig;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.epam.sdmxproxy.configuration.data.availability.UnfilteredAvailabilitySource;
import com.epam.sdmxproxy.exception.AvailabilityProbeQueuedException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.availability.harvest.CsvSeriesKeyHarvester;
import com.epam.sdmxproxy.services.availability.harvest.HarvestedCoverage;
import com.epam.sdmxproxy.services.availability.harvest.QueuedResponseDetector;
import com.epam.sdmxproxy.services.availability.harvest.SeriesKeyHarvesterProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The routing rules this design turns on, and the one that most needs guarding: a narrowed
 * request must never be answered from the content constraint, because that answer is the whole
 * cube presented as a narrowed one and nothing downstream would notice.
 */
class DataQueryAvailabilityEmulatorTest {

    private static final MediaType JSON_2_0_0 =
            MediaType.parseMediaType("application/vnd.sdmx.structure+json;version=2.0.0");

    private static final String PROBE_CSV = """
            DATAFLOW,LAST UPDATE,freq,unit,na_item,geo
            ESTAT:NAMA_10_GDP(1.0),03/09/26 23:00:00,A,CLV05_MEUR,B1GQ,EL
            ESTAT:NAMA_10_GDP(1.0),03/09/26 23:00:00,A,CP_MEUR,B1GQ,EL
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UnfilteredConstraintSource constraintSource;
    private ProbeDecomposer decomposer;
    private RecordingContext context;
    private DataQueryAvailabilityEmulator emulator;

    @BeforeEach
    void setUp() {
        constraintSource = mock(UnfilteredConstraintSource.class);
        decomposer = mock(ProbeDecomposer.class);
        context = new RecordingContext();

        SeriesKeyHarvesterProvider harvesterProvider =
                new SeriesKeyHarvesterProvider(List.of(new CsvSeriesKeyHarvester()));

        emulator = new DataQueryAvailabilityEmulator(
                new AvailabilityQueryCanonicalizer(),
                constraintSource,
                new ProbeQueryBuilder(),
                decomposer,
                harvesterProvider,
                new QueuedResponseDetector(),
                new AvailabilityConstraintSynthesizer(),
                new StreamingAvailabilityConversionService(objectMapper, null, null, null, null, null)
        );
    }

    @Test
    @DisplayName("an unfiltered request is served from the constraint, without probing")
    void unfilteredServedFromConstraint() {
        when(constraintSource.coverage(any(), any(), any())).thenReturn(coverage(
                Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR", "CP_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("AT", "EL"))));

        JsonNode json = parse(emulator.emulate(query("*", null), beans(), context));

        assertEquals(0, context.dataQueries.size(), "the constraint answers this request; a probe would be waste");
        assertEquals(Set.of("AT", "EL"), valuesOf(json, "geo"));
    }

    @Test
    @DisplayName("an unfiltered request probes when the source is PROBE")
    void unfilteredProbesWhenConfigured() {
        TranslatedAvailabilityQuery query = query("*", null);
        emulationOf(query).setUnfilteredSource(UnfilteredAvailabilitySource.PROBE);
        context.probeResponse = PROBE_CSV;

        JsonNode json = parse(emulator.emulate(query, beans(), context));

        assertEquals(1, context.dataQueries.size());
        verify(constraintSource, never()).coverage(any(), any(), any());
        assertEquals(Set.of("EL"), valuesOf(json, "geo"));
    }

    @Test
    @DisplayName("a narrowed request is probed with the client's own key")
    void narrowedRequestIsProbed() {
        when(constraintSource.coverage(any(), any(), any())).thenReturn(coverage(
                Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR", "CP_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("AT", "EL"))));
        context.probeResponse = PROBE_CSV;

        JsonNode json = parse(emulator.emulate(query("A...EL", null), beans(), context));

        assertEquals(1, context.dataQueries.size());
        assertEquals("A...EL", context.dataQueries.get(0).getKey(), "the probe must ask the client's question");
        assertEquals(Set.of("EL"), valuesOf(json, "geo"), "the answer must be narrowed, not the whole cube");
    }

    @Test
    @DisplayName("a narrowed request is NOT answered from the constraint when the probe fails")
    void narrowedRequestNeverFallsBackToTheConstraint() {
        // The regression this test exists for. The constraint would answer with every geo, which
        // is a superset -- so nothing would crash, and every narrowing decision downstream would
        // silently be made against the wrong set.
        when(constraintSource.coverage(any(), any(), any())).thenReturn(coverage(
                Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("AT", "BE", "EL"))));
        when(decomposer.plan(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());
        context.probeResponse = queuedEnvelope();

        TranslatedAvailabilityQuery query = query("A...EL", null);
        emulationOf(query).getAsyncRetry().setEnabled(false);

        assertThrows(AvailabilityProbeQueuedException.class,
                () -> emulator.emulate(query, beans(), context));
    }

    @Test
    @DisplayName("a probe value the constraint does not list is dropped to keep the subset invariant")
    void probeOnlyValuesAreDropped() {
        // A code present in a filtered response but absent from the load-time one is a bare
        // KeyError in the consumer.
        when(constraintSource.coverage(any(), any(), any())).thenReturn(coverage(
                Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("AT", "EL"))));
        context.probeResponse = PROBE_CSV;

        JsonNode json = parse(emulator.emulate(query("A...EL", null), beans(), context));

        // CP_MEUR came back from the probe but is not in the universe.
        assertEquals(Set.of("CLV05_MEUR"), valuesOf(json, "unit"));
    }

    @Test
    @DisplayName("a queued probe is retried at the same URL")
    void queuedProbeIsRetried() {
        when(constraintSource.coverage(any(), any(), any())).thenReturn(coverage(
                Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR", "CP_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("EL"))));
        context.probeResponse = queuedEnvelope();
        context.responseAfterFirstCall = PROBE_CSV;

        TranslatedAvailabilityQuery query = query("A...EL", null);
        AvailabilityAsyncRetryConfig retry = emulationOf(query).getAsyncRetry();
        retry.setInitialIntervalMillis(1L);
        retry.setMaxAttempts(3);

        JsonNode json = parse(emulator.emulate(query, beans(), context));

        assertEquals(2, context.dataQueries.size(), "the same request is re-issued, not a poll of another API");
        assertTrue(valuesOf(json, "geo").contains("EL"));
    }

    @Test
    @DisplayName("the intersection is skipped when there is no constraint to intersect against")
    void probeOnlyConfigurationSkipsIntersection() {
        TranslatedAvailabilityQuery query = query("A...EL", null);
        emulationOf(query).setUnfilteredSource(UnfilteredAvailabilitySource.PROBE);
        context.probeResponse = PROBE_CSV;

        JsonNode json = parse(emulator.emulate(query, beans(), context));

        verify(constraintSource, never()).coverage(any(), any(), any());
        assertEquals(Set.of("CLV05_MEUR", "CP_MEUR"), valuesOf(json, "unit"));
    }

    @SneakyThrows
    private JsonNode parse(byte[] rendered) {
        return objectMapper.readTree(rendered);
    }

    private static Set<String> valuesOf(JsonNode json, String dimensionId) {
        JsonNode keyValues = json.path("data").path("dataConstraints").get(0)
                .path("cubeRegions").get(0).path("keyValues");
        for (JsonNode keyValue : keyValues) {
            if (dimensionId.equals(keyValue.path("id").asText())) {
                Set<String> values = new LinkedHashSet<>();
                keyValue.path("values").forEach(value -> values.add(value.path("value").asText()));
                return values;
            }
        }
        throw new AssertionError("No key value for " + dimensionId);
    }

    private static HarvestedCoverage coverage(Map<String, Set<String>> values) {
        Map<String, Set<String>> ordered = new LinkedHashMap<>();
        for (String dimensionId : List.of("freq", "unit", "na_item", "geo")) {
            ordered.put(dimensionId, new LinkedHashSet<>(values.getOrDefault(dimensionId, Set.of())));
        }
        return new HarvestedCoverage(ordered, -1L);
    }

    private static String queuedEnvelope() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><env:Envelope "
                + "xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body>"
                + "<ns0:syncResponse xmlns:ns0=\"http://estat.ec.europa.eu/disschain/soap/extraction\">"
                + "<queued><id>a801f1c5</id><status>SUBMITTED</status></queued>"
                + "</ns0:syncResponse></env:Body></env:Envelope>";
    }

    private static AvailabilityEmulationConfiguration emulationOf(TranslatedAvailabilityQuery query) {
        return query.getVersionConfiguration().getAvailabilityEndpointConfig().getEmulation();
    }

    private static TranslatedAvailabilityQuery query(String key, MultiValueMap<String, String> filters) {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        emulation.setType(AvailabilityEmulationType.DATA_QUERY);
        emulation.setProbeFormat(SdmxFormat.CSV_DATA_1_0_0);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(false);
        availabilityConfig.setEmulation(emulation);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setSupportedFormats(List.of(SdmxFormat.CSV_DATA_1_0_0));
        dataConfig.setDefaultFormat(SdmxFormat.CSV_DATA_1_0_0);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);
        versionConfig.setDataEndpointConfig(dataConfig);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("ESTAT");

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .context("dataflow")
                .agencyID("ESTAT")
                .resourceID("NAMA_10_GDP")
                .version("1.0")
                .key(key)
                .filters(filters)
                .contentType(JSON_2_0_0)
                .build();
    }

    private static SdmxBeans beans() {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        DimensionListBean dimensionList = mock(DimensionListBean.class);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        when(dsd.getDimensionList()).thenReturn(dimensionList);

        List<DimensionBean> dimensions = new ArrayList<>();
        for (String id : List.of("freq", "unit", "na_item", "geo", "TIME_PERIOD")) {
            DimensionBean dimension = mock(DimensionBean.class);
            when(dimension.getId()).thenReturn(id);
            when(dimension.isTimeDimension()).thenReturn("TIME_PERIOD".equals(id));
            dimensions.add(dimension);
        }
        when(dimensionList.getDimensions()).thenReturn(dimensions);
        return beans;
    }

    /**
     * Records the probes the emulator issues, so the tests can assert what was asked upstream
     * rather than only what came back.
     */
    private static final class RecordingContext implements AvailabilityEmulationContext {

        private final List<TranslatedDataQuery> dataQueries = new ArrayList<>();
        private String probeResponse = "";
        private String responseAfterFirstCall;

        @Override
        public InputStream fetchStructure(TranslatedStructureQuery query) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public InputStream fetchData(TranslatedDataQuery query) {
            dataQueries.add(query);
            String body = dataQueries.size() > 1 && responseAfterFirstCall != null
                    ? responseAfterFirstCall
                    : probeResponse;
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public SdmxBeans fetchStructureBeans(TranslatedStructureQuery query) {
            throw new UnsupportedOperationException("the constraint source is mocked in these tests");
        }

        @Override
        public TranslatedStructureQuery structureQuery(
                String structureType, String agencyId, String resourceId, String version) {
            return TranslatedStructureQuery.builder().build();
        }
    }
}
