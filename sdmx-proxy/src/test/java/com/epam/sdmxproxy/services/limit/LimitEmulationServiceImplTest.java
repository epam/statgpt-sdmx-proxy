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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LimitEmulationServiceImplTest {

    private AvailabilityFixtureService availabilityFixtureService;
    private AvailabilityResponseParser parser;
    private FilterShrinker shrinker;
    private DimensionService dimensionService;
    private LimitEmulationServiceImpl service;

    /**
     * Capture the {@link TranslatedAvailabilityQuery} submitted to the last probe. Tests
     * use this to assert the shape of the outbound availability request (componentId,
     * filters, key, etc.) without the service needing a registry adapter.
     */
    private AtomicReference<TranslatedAvailabilityQuery> lastProbeQuery;

    @BeforeEach
    void setUp() {
        availabilityFixtureService = mock(AvailabilityFixtureService.class);
        parser = mock(AvailabilityResponseParser.class);
        shrinker = mock(FilterShrinker.class);
        dimensionService = mock(DimensionService.class);

        when(availabilityFixtureService.applyFixtures(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new LimitEmulationServiceImpl(
                availabilityFixtureService, parser, shrinker, dimensionService, new KeyParserImpl()
        );
        lastProbeQuery = new AtomicReference<>();
    }

    /** Returns a prober that records the availability query and hands back an empty stream. */
    private AvailabilityProber recordingProber() {
        return q -> {
            lastProbeQuery.set(q);
            return new ByteArrayInputStream(new byte[0]);
        };
    }

    // --- SDMX 3.0 path ---

    @Test
    void getShrunkQuery_mUnderTargetOnFirstProbe_oneProbe_noShrink() {
        TranslatedDataQuery query = baseQuery(10);
        SdmxBeans beans = mock(SdmxBeans.class);
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A", "D"))));

        when(parser.parse(any(), any())).thenReturn(tiny);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getLimit()).isNull();
        verify(shrinker, never()).decide(any(), anyLong(), any());
    }

    @Test
    void getShrunkQuery_sdmx30_stripsLimit_preservesClientKey() {
        TranslatedDataQuery query = baseQuery(10);
        SdmxBeans beans = mock(SdmxBeans.class);
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))));

        when(parser.parse(any(), any())).thenReturn(tiny);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getLimit()).isNull();
        assertThat(shrunk.getKey()).as("3.0 path leaves client key untouched").isEqualTo("*");
    }

    @Test
    void getShrunkQuery_sdmx30_deepCopiesClientFilters() {
        TranslatedDataQuery query = baseQuery(10);
        MultiValueMap<String, String> originalFilters = query.getFilters();
        originalFilters.add("FREQ", "A");

        SdmxBeans beans = mock(SdmxBeans.class);
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))));
        when(parser.parse(any(), any())).thenReturn(tiny);

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(lastProbeQuery.get().getFilters())
                .as("probe must receive a deep copy of client filters, not the same instance")
                .isNotSameAs(originalFilters);
    }

    // --- SDMX 2.1 path ---

    @Test
    void getShrunkQuery_sdmx21_mUnderTargetOnFirstProbe_noShrink() {
        TranslatedDataQuery query = baseQuery21(10, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))), 1L);
        when(parser.parse(any(), any())).thenReturn(tiny);

        service.getShrunkQuery(query, beans, recordingProber());

        verify(shrinker, never()).decide(any(), anyLong(), any());
    }

    @Test
    void getShrunkQuery_sdmx21_shrinkUpdatesKey_nullsFilters() {
        TranslatedDataQuery query = baseQuery21(10, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection wide = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE", "FR", "GB", "US", "JP"))),
                100L);
        AvailabilityProjection narrow = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE", "FR"))),
                4L);
        when(parser.parse(any(), any())).thenReturn(wide, narrow);
        when(shrinker.decide(any(), anyLong(), any())).thenReturn(
                new ShrinkDecision("REF_AREA", List.of("DE", "FR")));

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getLimit()).isNull();
        assertThat(shrunk.getFilters())
                .as("2.1 path must null out filters -- narrowing lives in the key")
                .isNull();
        assertThat(shrunk.getKey())
                .as("shrunk key should reflect REF_AREA retained values via + separator")
                .contains("DE+FR");
    }

    @Test
    void getShrunkQuery_sdmx21_passesConcreteComponentIdAndNoFiltersToAvailability() {
        TranslatedDataQuery query = baseQuery21(10, "*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))), 1L);
        when(parser.parse(any(), any())).thenReturn(tiny);

        service.getShrunkQuery(query, beans, recordingProber());

        assertThat(lastProbeQuery.get().getComponentId())
                .as("2.1 availability requires concrete joined componentId, not '*'")
                .isEqualTo("FREQ,REF_AREA");
        assertThat(lastProbeQuery.get().getFilters())
                .as("2.1 availability must not send any c[] filters")
                .isNull();
    }

    @Test
    void getShrunkQuery_sdmx21_preservesStartEndPeriod() {
        TranslatedDataQuery query = baseQuery21(10, "*").toBuilder()
                .startPeriod("2020-01-01")
                .endPeriod("2020-12-31")
                .build();
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection tiny = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of("FREQ", List.of("A"))), 1L);
        when(parser.parse(any(), any())).thenReturn(tiny);

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getStartPeriod()).isEqualTo("2020-01-01");
        assertThat(shrunk.getEndPeriod()).isEqualTo("2020-12-31");
    }

    @Test
    void getShrunkQuery_sdmx21_allKeyword_expandsAndShrinks() {
        TranslatedDataQuery query = baseQuery21(2, "all");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection wide = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE", "FR", "GB", "US"))),
                50L);
        AvailabilityProjection shrunkProj = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE"))),
                2L);
        when(parser.parse(any(), any())).thenReturn(wide, shrunkProj);
        when(shrinker.decide(any(), anyLong(), any())).thenReturn(
                new ShrinkDecision("REF_AREA", List.of("DE")));

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getKey()).isEqualTo("*.DE");
    }

    @Test
    void getShrunkQuery_sdmx21_multiValuePositionInClientKey() {
        TranslatedDataQuery query = baseQuery21(5, "M+D.*");
        SdmxBeans beans = mockBeansWithDims("FREQ", "REF_AREA", "TIME_PERIOD");
        AvailabilityProjection wide = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE", "FR", "GB"))),
                30L);
        AvailabilityProjection narrow = new AvailabilityProjection(
                new LinkedHashMap<>(Map.of(
                        "FREQ", List.of("M", "D"),
                        "REF_AREA", List.of("DE"))),
                4L);
        when(parser.parse(any(), any())).thenReturn(wide, narrow);
        when(shrinker.decide(any(), anyLong(), any())).thenReturn(
                new ShrinkDecision("REF_AREA", List.of("DE")));

        TranslatedDataQuery shrunk = service.getShrunkQuery(query, beans, recordingProber());

        assertThat(shrunk.getKey())
                .as("FREQ position preserved as M+D, REF_AREA narrowed to DE")
                .isEqualTo("M+D.DE");
    }

    // --- helpers ---

    private TranslatedDataQuery baseQuery(Integer limit) {
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
        dataCfg.setLimitEmulationMaxShrinkIterations(32);
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
}
