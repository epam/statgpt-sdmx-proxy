package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.misc.DimensionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LimitEmulationServiceImplTest {

    private AvailabilityFixtureService availabilityFixtureService;
    private AvailabilityResponseParser parser;
    private DimensionService dimensionService;
    private CodelistSizeResolver codelistSizeResolver;
    private LimitEmulationServiceImpl service;

    private AtomicReference<TranslatedAvailabilityQuery> lastProbeQuery;
    private AtomicInteger probeCount;

    @BeforeEach
    void setUp() {
        availabilityFixtureService = mock(AvailabilityFixtureService.class);
        parser = mock(AvailabilityResponseParser.class);
        dimensionService = mock(DimensionService.class);
        codelistSizeResolver = mock(CodelistSizeResolver.class);

        when(availabilityFixtureService.applyFixtures(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(codelistSizeResolver.resolveSize(any(), any())).thenReturn(OptionalInt.empty());

        service = new LimitEmulationServiceImpl(
                availabilityFixtureService,
                parser,
                new BisectCalculator(),
                codelistSizeResolver,
                dimensionService,
                new KeyParserImpl()
        );

        lastProbeQuery = new AtomicReference<>();
        probeCount = new AtomicInteger();
    }

    private AvailabilityProber recordingProber() {
        return q -> {
            lastProbeQuery.set(q);
            probeCount.incrementAndGet();
            return new ByteArrayInputStream(new byte[0]);
        };
    }

    // ===================== TC fast-path =====================

    @Test
    void getShrunkQuery_tcFastPath_skipsAvailabilityProbe_whenCubeFitsInLimit() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        // FREQ codelist size = 5, REF_AREA codelist size = 100 -> TC = 500 <= target=1200
        when(codelistSizeResolver.resolveSize(any(), any()))
                .thenReturn(OptionalInt.of(5))
                .thenReturn(OptionalInt.of(100));

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isZero();
        assertThat(shrunk.getLimit()).isNull();
        verify(parser, never()).parse(any(), any());
    }

    @Test
    void getShrunkQuery_tcFastPath_fallsBackToProbe_whenCodelistUnresolvable() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        when(codelistSizeResolver.resolveSize(any(), any())).thenReturn(OptionalInt.empty());
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))), 1L);
        when(parser.parse(any(), any())).thenReturn(tiny);

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(1);
    }

    @Test
    void getShrunkQuery_tcFastPath_usesClientFilterSize_when3_0() {
        TranslatedDataQuery query = baseQuery30(1000);
        // Client filtered FREQ to 1 value, REF_AREA wildcard
        query.getFilters().add("FREQ", "M");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        // FREQ has client filter -> use 1; REF_AREA wildcard -> use codelist size 50; TC=50 <= 1200
        when(codelistSizeResolver.resolveSize(any(), any())).thenReturn(OptionalInt.of(50));

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isZero();
    }

    // ===================== Initial probe lands in band / under target =====================

    @Test
    void getShrunkQuery_initialProbeUnderTarget_oneProbe_noBisect() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("A", "M"),
                        "REF_AREA", List.of("DE", "FR", "GB"))),
                1100L);
        when(parser.parse(any(), any())).thenReturn(inBand);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(1);
        assertThat(shrunk.getLimit()).isNull();
        assertThat(shrunk.getKey()).isEqualTo("*");
    }

    // ===================== Single-dim bisect: warm-start hits =====================

    @Test
    void getShrunkQuery_warmStartHitsBand_twoProbes() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L,
                "REF_AREA", values(100),
                "FREQ", List.of("A", "M"));
        AvailabilityProjection afterShrink = mkProjection(1100L,
                "REF_AREA", values(30),
                "FREQ", List.of("A", "M"));
        when(parser.parse(any(), any())).thenReturn(initial, afterShrink);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(2);
        assertThat(shrunk.getFilters().get("REF_AREA")).hasSize(30);
    }

    // ===================== Bisect: multiple steps =====================

    @Test
    void getShrunkQuery_bisect_overshootThenIntoBand() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L,
                "REF_AREA", values(100),
                "FREQ", List.of("A", "M"));
        // First shrink (k=30 from warm-start) returns SC=2000 -> overshoot, kHigh=30
        AvailabilityProjection overshoot = mkProjection(2000L,
                "REF_AREA", values(30),
                "FREQ", List.of("A", "M"));
        // Re-step: k = ceil(1200 * 30 / 2000) = 18; mock returns SC=1100 -> in band
        AvailabilityProjection inBand = mkProjection(1100L,
                "REF_AREA", values(18),
                "FREQ", List.of("A", "M"));
        when(parser.parse(any(), any())).thenReturn(initial, overshoot, inBand);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(3);
        assertThat(shrunk.getFilters().get("REF_AREA")).hasSize(18);
    }

    // ===================== Integer gap: lock at kHigh =====================

    @Test
    void getShrunkQuery_integerGap_locksAtKHigh_keepsOvershootSide() {
        // Single-dim DSD so the outer loop ends after this dim's bisect locks at k_high.
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L, "REF_AREA", values(3));
        // Warm-start k=ceil(1200*3/4000)=1; probe k=1 -> SC=500 (undershoot, kLow=1)
        AvailabilityProjection k1 = mkProjection(500L, "REF_AREA", values(1));
        // Bisect midpoint k=2 -> SC=1500 (overshoot, kHigh=2). Integer gap (kHigh-kLow=1) -> lock.
        AvailabilityProjection k2Overshoot = mkProjection(1500L, "REF_AREA", values(2));
        when(parser.parse(any(), any())).thenReturn(initial, k1, k2Overshoot);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(3);
        assertThat(shrunk.getFilters().get("REF_AREA")).hasSize(2);
    }

    // ===================== NO_OVERSHOOT_FOUND: revert dim, try next =====================

    @Test
    void getShrunkQuery_noOvershootOnFirstDim_revertsAndTriesNextDim() {
        TranslatedDataQuery query = baseQuery30(1000);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L,
                "REF_AREA", values(10),
                "FREQ", List.of("A", "M"));
        // Warm-start on REF_AREA: k = ceil(1200*10/4000) = 3 -> SC=200 (undershoot, kLow=3)
        AvailabilityProjection refAreaK3 = mkProjection(200L,
                "REF_AREA", values(3),
                "FREQ", List.of("A", "M"));
        // Re-step: k = ceil(1200 * 3 / 200) = 18 -> clamp to (3, 10) = 9
        // probe k=9 -> SC=700 (still undershoot, kLow=9). kHigh-kLow=1, kHigh==dimSize -> NO_OVERSHOOT_FOUND
        AvailabilityProjection refAreaK9 = mkProjection(700L,
                "REF_AREA", values(9),
                "FREQ", List.of("A", "M"));
        // dim FREQ next: warm-start k=ceil(1200*2/4000)=1; probe k=1 -> SC=1100 (in band!)
        AvailabilityProjection freqK1 = mkProjection(1100L,
                "REF_AREA", values(10),
                "FREQ", List.of("A"));
        when(parser.parse(any(), any())).thenReturn(initial, refAreaK3, refAreaK9, freqK1);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isEqualTo(4);
        // REF_AREA should be reverted (no entry in filters, since client didn't set one originally)
        assertThat(shrunk.getFilters().get("REF_AREA")).isNull();
        // FREQ locked at k=1
        assertThat(shrunk.getFilters().get("FREQ")).containsExactly("A");
    }

    // ===================== Probe budget exhaustion =====================

    @Test
    void getShrunkQuery_probeBudgetExhausted_locksAtBestKnownOvershoot() {
        TranslatedDataQuery query = baseQuery30(1000);
        // Lower budget to make exhaustion easy to trigger
        query.getVersionConfiguration().getDataEndpointConfig().setLimitEmulationProbeBudget(3);

        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L,
                "REF_AREA", values(100),
                "FREQ", List.of("A", "M"));
        // Each subsequent probe alternates over/under without converging in budget
        AvailabilityProjection p2 = mkProjection(2000L, "REF_AREA", values(30), "FREQ", List.of("A", "M"));
        AvailabilityProjection p3 = mkProjection(500L, "REF_AREA", values(15), "FREQ", List.of("A", "M"));
        when(parser.parse(any(), any())).thenReturn(initial, p2, p3);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(probeCount.get()).isLessThanOrEqualTo(4);
        // After budget exhaust, the dim should be locked at last known kHigh = 30 (the overshoot side)
        assertThat(shrunk.getFilters().get("REF_AREA")).hasSize(30);
    }

    // ===================== SDMX 2.1 path =====================

    @Test
    void getShrunkQuery_sdmx21_initialUnderTarget_keepsClientKey() {
        TranslatedDataQuery query = baseQuery21(1000, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = mkProjection(900L,
                "FREQ", List.of("A"),
                "REF_AREA", List.of("DE"));
        when(parser.parse(any(), any())).thenReturn(inBand);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getLimit()).isNull();
        assertThat(shrunk.getFilters()).isNull();
    }

    @Test
    void getShrunkQuery_sdmx21_bisectShrinksKey_warmStartInBand() {
        TranslatedDataQuery query = baseQuery21(1000, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection initial = mkProjection(4000L,
                "REF_AREA", values(100),
                "FREQ", List.of("A", "M"));
        AvailabilityProjection afterShrink = mkProjection(1100L,
                "REF_AREA", values(30),
                "FREQ", List.of("A", "M"));
        when(parser.parse(any(), any())).thenReturn(initial, afterShrink);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getKey()).isNotNull();
        // Key must contain narrowed REF_AREA values (dim 1 in our DSD), 30 of them joined by '+'
        assertThat(shrunk.getKey().split("\\.")[1].split("\\+")).hasSize(30);
        assertThat(shrunk.getFilters()).isNull();
    }

    @Test
    void getShrunkQuery_sdmx21_passesConcreteComponentIdAndNoFiltersToAvailability() {
        TranslatedDataQuery query = baseQuery21(1000, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = mkProjection(800L,
                "FREQ", List.of("A"),
                "REF_AREA", List.of("DE"));
        when(parser.parse(any(), any())).thenReturn(inBand);

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(lastProbeQuery.get().getComponentId()).isEqualTo("FREQ,REF_AREA");
        assertThat(lastProbeQuery.get().getFilters()).isNull();
    }

    @Test
    void getShrunkQuery_sdmx21_preservesStartEndPeriod() {
        TranslatedDataQuery query = baseQuery21(1000, "*").toBuilder()
                .startPeriod("2020-01-01")
                .endPeriod("2020-12-31")
                .build();
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = mkProjection(500L, "FREQ", List.of("A"));
        when(parser.parse(any(), any())).thenReturn(inBand);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getStartPeriod()).isEqualTo("2020-01-01");
        assertThat(shrunk.getEndPeriod()).isEqualTo("2020-12-31");
    }

    @Test
    void getShrunkQuery_sdmx30_deepCopiesClientFilters() {
        TranslatedDataQuery query = baseQuery30(1000);
        MultiValueMap<String, String> originalFilters = query.getFilters();
        originalFilters.add("FREQ", "A");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = mkProjection(800L, "FREQ", List.of("A"));
        when(parser.parse(any(), any())).thenReturn(inBand);

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(lastProbeQuery.get().getFilters())
                .as("probe must receive a deep copy of client filters, not the same instance")
                .isNotSameAs(originalFilters);
    }

    @Test
    void getShrunkQuery_sdmx21_appliesMergeAllWildcardKey_whenAllWildcard() {
        TranslatedDataQuery query = baseQuery21(1000, "*");
        query.getVersionConfiguration().getDataEndpointConfig().setMergeAllWildcardKey(true);
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection inBand = mkProjection(500L,
                "FREQ", List.of("A"),
                "REF_AREA", List.of("DE"));
        when(parser.parse(any(), any())).thenReturn(inBand);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getKey()).isEqualTo("*");
    }

    // ===================== helpers =====================

    private TranslatedDataQuery baseQuery30(Integer limit) {
        return newQuery(SdmxVersion.SDMX_3_0, limit, "*", new LinkedMultiValueMap<>());
    }

    private TranslatedDataQuery baseQuery21(Integer limit, String key) {
        return newQuery(SdmxVersion.SDMX_2_1, limit, key, new LinkedMultiValueMap<>());
    }

    private TranslatedDataQuery newQuery(
            SdmxVersion sdmxVersion,
            Integer limit,
            String key,
            MultiValueMap<String, String> filters
    ) {
        DataEndpointConfiguration dataCfg = new DataEndpointConfiguration();
        dataCfg.setSupportsLimit(false);
        dataCfg.setLimitEmulationTolerance(1.2);
        dataCfg.setLimitEmulationProbeBudget(8);
        dataCfg.setDefaultFormat(ReturnFormat.JSON_1_0_0);

        AvailabilityEndpointConfiguration availCfg = new AvailabilityEndpointConfiguration();
        availCfg.setAvailabilityEnabled(true);
        availCfg.setUnwrapStarComponentId(false);
        availCfg.setDefaultFormat(ReturnFormat.JSON_STRUCTURE_2_0_0);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(sdmxVersion);
        versionConfig.setDataEndpointConfig(dataCfg);
        versionConfig.setAvailabilityEndpointConfig(availCfg);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");

        return TranslatedDataQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .agencyID("AGY")
                .resourceID("RES")
                .version("1.0")
                .key(key)
                .filters(filters)
                .returnFormat(ReturnFormat.JSON_1_0_0)
                .limit(limit)
                .build();
    }

    private static SdmxBeans mockBeansWithDims(String... dims) {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        DimensionListBean dimList = mock(DimensionListBean.class);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        when(dsd.getDimensionList()).thenReturn(dimList);
        List<DimensionBean> beanList = new ArrayList<>();
        for (String id : dims) {
            DimensionBean d = mock(DimensionBean.class);
            when(d.getId()).thenReturn(id);
            when(d.isTimeDimension()).thenReturn("TIME_PERIOD".equals(id));
            beanList.add(d);
        }
        when(dimList.getDimensions()).thenReturn(beanList);
        return beans;
    }

    private static AvailabilityProjection mkProjection(long seriesCount, Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> vals = (List<String>) kv[i + 1];
            m.put((String) kv[i], vals);
        }
        return new AvailabilityProjection(m, seriesCount);
    }

    private static List<String> values(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add("V" + i);
        }
        return out;
    }
}
