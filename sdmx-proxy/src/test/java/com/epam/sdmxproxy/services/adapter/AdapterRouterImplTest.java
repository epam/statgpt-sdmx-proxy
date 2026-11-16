package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.exception.RateLimitExceededException;
import com.epam.sdmxproxy.exception.StructureFanOutException;
import com.epam.sdmxproxy.exception.StructureListNotSupportedException;
import com.epam.sdmxproxy.exception.StructureNotFoundException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.exception.UnsupportedConversionException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.availability.AvailabilityEmulationContext;
import com.epam.sdmxproxy.services.availability.AvailabilityEmulator;
import com.epam.sdmxproxy.services.availability.AvailabilityEmulatorProvider;
import com.epam.sdmxproxy.services.availability.AvailabilityQueryCanonicalizer;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.filter.FilterNormalizer;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.fixture.data.DataFixtureService;
import com.epam.sdmxproxy.services.fixture.data.MetadataAttributesPreserver;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageFolder;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsageXmlInjector;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.limit.LimitEmulationService;
import com.epam.sdmxproxy.services.limit.truncate.SeriesLimitTruncatorProvider;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdapterRouterImplTest {

    private GenericRegistryAdapter genericRegistryAdapter;
    private CacheService cacheService;
    private StructureFixtureService structureFixtureService;
    private StreamingStructureConversionService streamingStructureConversionService;
    private MetadataAttributeUsagePreserver metadataAttributeUsagePreserver;
    private MetadataAttributeUsageXmlInjector metadataAttributeUsageXmlInjector;
    private AvailabilityEmulatorProvider availabilityEmulatorProvider;
    private QueryTranslator queryTranslator;
    private DataFixtureService dataFixtureService;
    private MatchAllKeyExpander matchAllKeyExpander;
    private AdapterRouterImpl router;

    @BeforeEach
    void setUp() {
        StreamingDataConversionService streamingDataConversionService = mock(StreamingDataConversionService.class);
        streamingStructureConversionService = mock(StreamingStructureConversionService.class);
        StreamingAvailabilityConversionService streamingAvailabilityConversionService = mock(StreamingAvailabilityConversionService.class);
        genericRegistryAdapter = mock(GenericRegistryAdapter.class);
        queryTranslator = mock(QueryTranslator.class);
        cacheService = mock(CacheService.class);
        structureFixtureService = mock(StructureFixtureService.class);
        metadataAttributeUsagePreserver = mock(MetadataAttributeUsagePreserver.class);
        MetadataAttributeUsageFolder metadataAttributeUsageFolder = mock(MetadataAttributeUsageFolder.class);
        metadataAttributeUsageXmlInjector = mock(MetadataAttributeUsageXmlInjector.class);
        MetadataAttributesPreserver metadataAttributesPreserver = mock(MetadataAttributesPreserver.class);
        AvailabilityFixtureService availabilityFixtureService = mock(AvailabilityFixtureService.class);
        dataFixtureService = mock(DataFixtureService.class);
        matchAllKeyExpander = mock(MatchAllKeyExpander.class);
        LimitEmulationService limitEmulationService = mock(LimitEmulationService.class);
        SeriesLimitTruncatorProvider truncatorProvider = mock(SeriesLimitTruncatorProvider.class);
        FilterNormalizer filterNormalizer = mock(FilterNormalizer.class);
        ObjectMapper objectMapper = new ObjectMapper();
        availabilityEmulatorProvider = mock(AvailabilityEmulatorProvider.class);

        router = new AdapterRouterImpl(
                streamingDataConversionService,
                streamingStructureConversionService,
                streamingAvailabilityConversionService,
                genericRegistryAdapter,
                queryTranslator,
                cacheService,
                structureFixtureService,
                metadataAttributeUsagePreserver,
                metadataAttributeUsageFolder,
                metadataAttributeUsageXmlInjector,
                metadataAttributesPreserver,
                availabilityFixtureService,
                dataFixtureService,
                limitEmulationService,
                truncatorProvider,
                filterNormalizer,
                objectMapper,
                availabilityEmulatorProvider,
                new AvailabilityQueryCanonicalizer(),
                new NoDataFaultDetector(),
                matchAllKeyExpander
        );
    }

    @Test
    void getSdmxBeans_returnsEmptyBeans_whenRegistryReturnsNullStream() {
        TranslatedStructureQuery query = buildStructureQuery("TEST_REG");
        when(cacheService.getRawStructures(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenReturn(null);
        when(structureFixtureService.applyFixtures(any(), any(), any())).thenReturn(null);

        SdmxBeans beans = router.getSdmxBeans(query);

        assertNotNull(beans);
        assertTrue(beans.getDataStructures().isEmpty());
        assertTrue(beans.getDataflows().isEmpty());
        assertTrue(beans.getCodelists().isEmpty());
        verify(streamingStructureConversionService, never()).parseStructures(any(), any());
        verify(cacheService, never()).putRawStructures(any(), any());
    }

    @Test
    void getStructuresWithFanOut_mergesAndCachesWhenAllLegsSucceed() throws IOException {
        AdapterRouterImpl partialMock = spy(router);
        TranslatedStructureQuery q1 = buildStructureQuery("BIS");
        TranslatedStructureQuery q2 = buildStructureQuery("IMF");
        TranslatedStructureQuery q3 = buildStructureQuery("ECB");
        doReturn(new SdmxBeansImpl()).when(partialMock).getSdmxBeans(q1);
        doReturn(new SdmxBeansImpl()).when(partialMock).getSdmxBeans(q2);
        doReturn(new SdmxBeansImpl()).when(partialMock).getSdmxBeans(q3);

        String cacheKey = "response:structure:fanout:dataflow:*:*::full:hash";
        StreamingResponseBody body = partialMock.getStructuresWithFanOut(List.of(q1, q2, q3), cacheKey);
        body.writeTo(new ByteArrayOutputStream());

        verify(partialMock, times(1)).getSdmxBeans(q1);
        verify(partialMock, times(1)).getSdmxBeans(q2);
        verify(partialMock, times(1)).getSdmxBeans(q3);
        verify(streamingStructureConversionService, times(1)).convert(any(SdmxBeans.class), any(), any(MediaType.class));
        verify(cacheService, times(1)).putReadyResponse(eq(cacheKey), any(byte[].class));
    }

    @Test
    void getStructuresWithFanOut_swallowsPerLegFailuresAndSkipsCacheWhenAnyLegFails() throws IOException {
        AdapterRouterImpl partialMock = spy(router);
        TranslatedStructureQuery q1 = buildStructureQuery("BIS");
        TranslatedStructureQuery q2 = buildStructureQuery("IMF");
        TranslatedStructureQuery q3 = buildStructureQuery("ECB");
        doReturn(new SdmxBeansImpl()).when(partialMock).getSdmxBeans(q1);
        doThrow(new RuntimeException("upstream down")).when(partialMock).getSdmxBeans(q2);
        doReturn(new SdmxBeansImpl()).when(partialMock).getSdmxBeans(q3);

        String cacheKey = "response:structure:fanout:dataflow:*:*::full:hash";
        StreamingResponseBody body = partialMock.getStructuresWithFanOut(List.of(q1, q2, q3), cacheKey);
        body.writeTo(new ByteArrayOutputStream());

        verify(streamingStructureConversionService, times(1)).convert(any(SdmxBeans.class), any(), any(MediaType.class));
        verify(cacheService, never()).putReadyResponse(any(), any(byte[].class));
    }

    @Test
    void getStructuresWithFanOut_throwsFanOutExceptionWhenAllLegsFail() {
        AdapterRouterImpl partialMock = spy(router);
        TranslatedStructureQuery q1 = buildStructureQuery("BIS");
        TranslatedStructureQuery q2 = buildStructureQuery("IMF");
        doThrow(new RuntimeException("bis down")).when(partialMock).getSdmxBeans(q1);
        doThrow(new RuntimeException("imf down")).when(partialMock).getSdmxBeans(q2);

        String cacheKey = "response:structure:fanout:dataflow:*:*::full:hash";
        StreamingResponseBody body = partialMock.getStructuresWithFanOut(List.of(q1, q2), cacheKey);

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(StructureFanOutException.class)
                .hasMessageContaining("BIS")
                .hasMessageContaining("IMF");
        verify(cacheService, never()).putReadyResponse(any(), any(byte[].class));
    }

    @Test
    void getStructuresWithFanOut_failedRegistriesAvailableOnException() {
        AdapterRouterImpl partialMock = spy(router);
        TranslatedStructureQuery q1 = buildStructureQuery("BIS");
        TranslatedStructureQuery q2 = buildStructureQuery("IMF");
        doThrow(new RuntimeException("bis down")).when(partialMock).getSdmxBeans(q1);
        doThrow(new RuntimeException("imf down")).when(partialMock).getSdmxBeans(q2);

        StreamingResponseBody body = partialMock.getStructuresWithFanOut(List.of(q1, q2), "key");

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(StructureFanOutException.class, ex ->
                        assertThat(ex.getFailedRegistries()).containsExactlyInAnyOrder("BIS", "IMF"));
    }

    @Test
    void getStructuresWithFanOut_emptyListThrowsUnexpectedState() {
        assertThatThrownBy(() -> router.getStructuresWithFanOut(List.of(), "key"))
                .isInstanceOf(UnexpectedStateException.class);
    }

    @Test
    void getStructuresWithFanOut_nullListThrowsUnexpectedState() {
        assertThatThrownBy(() -> router.getStructuresWithFanOut(null, "key"))
                .isInstanceOf(UnexpectedStateException.class);
    }

    @Test
    void getStructures_xml30WithUsageMarker_invokesXmlInjectorNotJsonPreserver() throws IOException {
        TranslatedStructureQuery query = buildXml30Query();
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenReturn(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        when(metadataAttributeUsagePreserver.isEnabled(any())).thenReturn(true);
        Map<String, JsonNode> captured = Map.of("IMF.STA|DSD_QNEA|7.0.0", new ObjectMapper().createArrayNode());
        when(metadataAttributeUsagePreserver.capture(any())).thenReturn(captured);
        when(structureFixtureService.applyFixtures(any(), any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(metadataAttributeUsageXmlInjector.inject(any(), any())).thenReturn("<injected/>".getBytes(StandardCharsets.UTF_8));

        StreamingResponseBody body = router.getStructures(query);
        body.writeTo(new ByteArrayOutputStream());

        verify(metadataAttributeUsageXmlInjector, times(1)).inject(any(), eq(captured));
        verify(metadataAttributeUsagePreserver, never()).inject(any(), any());
        verify(cacheService, times(1)).putReadyResponse(any(), any(byte[].class));
    }

    // ========== Design 043: match-all key expansion, status pass-through, probe repair ==========

    @Test
    void shouldExpandMatchAllKeyForData() throws IOException {
        stubStructures();
        when(matchAllKeyExpander.expand(any())).thenReturn("*.2+3+1.*");
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        router.getData(dataQuery("all", true, false)).writeTo(new ByteArrayOutputStream());

        assertThat(sentDataQuery().getKey()).isEqualTo("*.2+3+1.*");
    }

    @Test
    void shouldExpandMatchAllKeyWithTimeOnlyFilter() throws IOException {
        stubStructures();
        when(matchAllKeyExpander.expand(any())).thenReturn("*.2+3+1.*");
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        TranslatedDataQuery query = dataQuery("all", true, false);
        // The 2.1 translator keeps the time filter in `filters` after deriving startPeriod from it.
        LinkedMultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ge:2020");
        query.setFilters(filters);
        query.setStartPeriod("2020");

        router.getData(query).writeTo(new ByteArrayOutputStream());

        TranslatedDataQuery sent = sentDataQuery();
        assertThat(sent.getKey()).isEqualTo("*.2+3+1.*");
        assertThat(sent.getStartPeriod()).isEqualTo("2020");
    }

    @Test
    void shouldNotExpandNarrowedKey() throws IOException {
        stubStructures();
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        router.getData(dataQuery("1.*.*", true, false)).writeTo(new ByteArrayOutputStream());

        assertThat(sentDataQuery().getKey()).isEqualTo("1.*.*");
        verify(matchAllKeyExpander, never()).expand(any());
    }

    @Test
    void shouldNotExpandWithoutTheFlag() throws IOException {
        stubStructures();
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        router.getData(dataQuery("all", false, false)).writeTo(new ByteArrayOutputStream());

        assertThat(sentDataQuery().getKey()).isEqualTo("all");
        verify(matchAllKeyExpander, never()).expand(any());
    }

    @Test
    void shouldExpandMatchAllKeyOnBypass() throws IOException {
        stubStructures();
        when(matchAllKeyExpander.expand(any())).thenReturn("*.2+3+1.*");
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        router.getData(dataQuery("all", true, true)).writeTo(new ByteArrayOutputStream());

        assertThat(sentDataQuery().getKey()).isEqualTo("*.2+3+1.*");
    }

    @Test
    void shouldExpandMatchAllKeyForAvailabilityProbeAndRepairIt() {
        stubStructures();
        when(matchAllKeyExpander.expand(any())).thenReturn("*.2+3+1.*");
        when(cacheService.getEmulatedAvailability(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getData(any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(dataFixtureService.applyFixtures(any(), any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        AvailabilityEmulator emulator = mock(AvailabilityEmulator.class);
        when(availabilityEmulatorProvider.forType(AvailabilityEmulationType.DATA_QUERY)).thenReturn(emulator);
        TranslatedDataQuery probe = dataQuery("all", true, false);
        when(emulator.emulate(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, AvailabilityEmulationContext.class).fetchData(probe).close();
            return new byte[0];
        });

        router.getAvailability(emulatedAvailabilityQuery(probe.getVersionConfiguration()));

        assertThat(sentDataQuery().getKey()).isEqualTo("*.2+3+1.*");
        verify(dataFixtureService).applyFixtures(any(), eq(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1), isNull(), eq(probe.getVersionConfiguration().getDataEndpointConfig().getFixtures()), eq(Set.of(DataFixtureType.REPAIR_EMPTY_DATASET)));
    }

    @Test
    void shouldAnswer404ForUnknownStatCanArtefact() {
        TranslatedStructureQuery query = buildStructureQuery();
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenThrow(new StructureNotFoundException("No Statistics Canada codelist 'CL_Geography'"));

        StreamingResponseBody body = router.getStructures(query);

        // A StructureConversionException here would reach the client as 500.
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream())).isInstanceOf(StructureNotFoundException.class);
    }

    @Test
    void shouldAnswer501ForStatCanCodelistList() {
        TranslatedStructureQuery query = buildStructureQuery();
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenThrow(new StructureListNotSupportedException("listing every codelist is not supported"));

        StreamingResponseBody body = router.getStructures(query);

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream())).isInstanceOf(StructureListNotSupportedException.class);
    }

    @Test
    void shouldKeep429ForRateLimitOnStructureConversion() {
        TranslatedStructureQuery query = buildStructureQuery();
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenThrow(new RateLimitExceededException("rate limit"));

        StreamingResponseBody body = router.getStructures(query);

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream())).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void shouldKeep501ForUnsupportedConversion() {
        TranslatedStructureQuery query = buildStructureQuery();
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        when(genericRegistryAdapter.getStructures(query)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(structureFixtureService.applyFixtures(any(), any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        doThrow(new UnsupportedConversionException("Json for SDMX 2.1 (JSON v1.0) is not currently supported")).when(streamingStructureConversionService).convert(any(java.io.InputStream.class), any(), any(), any());

        StreamingResponseBody body = router.getStructures(query);

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream())).isInstanceOf(UnsupportedConversionException.class);
    }

    private void stubStructures() {
        when(queryTranslator.translateStructureQuery(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(buildStructureQuery());
        when(cacheService.getRawStructures(any())).thenReturn(Optional.of("<structures/>".getBytes(StandardCharsets.UTF_8)));
        when(streamingStructureConversionService.parseStructures(any(), any())).thenReturn(new SdmxBeansImpl());
    }

    private TranslatedDataQuery sentDataQuery() {
        ArgumentCaptor<TranslatedDataQuery> captor = ArgumentCaptor.forClass(TranslatedDataQuery.class);
        verify(genericRegistryAdapter).getData(captor.capture());
        return captor.getValue();
    }

    private static TranslatedDataQuery dataQuery(String key, boolean expandMatchAllKey, boolean bypass) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("StatCan");
        FixtureConfiguration<DataFixtureType> repair = new FixtureConfiguration<>();
        repair.setType(DataFixtureType.REPAIR_EMPTY_DATASET);
        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setExpandMatchAllKey(expandMatchAllKey);
        dataConfig.setBypassEnabled(bypass);
        dataConfig.setSupportedFormats(List.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
        dataConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1);
        dataConfig.setFixtures(List.of(repair));
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);
        versionConfig.setDataEndpointConfig(dataConfig);
        return TranslatedDataQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .agencyID("StatCan")
                .resourceID("DF_17100005")
                .version("1.0")
                .key(key)
                .contentType(MediaType.parseMediaType(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1.getContentType()))
                .returnFormat(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1)
                .build();
    }

    private static TranslatedAvailabilityQuery emulatedAvailabilityQuery(VersionSpecificRegistryConfiguration versionConfig) {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        emulation.setType(AvailabilityEmulationType.DATA_QUERY);
        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(false);
        availabilityConfig.setEmulation(emulation);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("StatCan");
        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .agencyID("StatCan")
                .resourceID("DF_17100005")
                .version("1.0")
                .key("all")
                .mode("exact")
                .contentType(MediaType.parseMediaType(SdmxMediaTypes.STRUCTURE_JSON_2_0_0))
                .returnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private static TranslatedStructureQuery buildXml30Query() {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("IMF");
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .structure(new Structure("datastructure", "IMF.STA", "DSD_QNEA", "7.0.0"))
                .references("descendants")
                .detail("full")
                .contentType(MediaType.valueOf(SdmxMediaTypes.STRUCTURE_XML_3_0_0))
                .registryReturnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0)
                .build();
    }

    private static TranslatedStructureQuery buildStructureQuery() {
        return buildStructureQuery("TEST_REG");
    }

    private static TranslatedStructureQuery buildStructureQuery(String registryName) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(registryName);
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .structure(new Structure("dataflow", "TEST_AGENCY", "TEST_FLOW", "1.0"))
                .references("descendants")
                .detail("full")
                .contentType(MediaType.APPLICATION_JSON)
                .registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }
}
