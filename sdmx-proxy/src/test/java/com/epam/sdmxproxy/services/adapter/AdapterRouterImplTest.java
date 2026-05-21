package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.StructureFanOutException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.filter.FilterNormalizer;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import com.epam.sdmxproxy.services.fixture.data.DataFixtureService;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.limit.LimitEmulationService;
import com.epam.sdmxproxy.services.limit.truncate.SeriesLimitTruncatorProvider;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private AdapterRouterImpl router;

    @BeforeEach
    void setUp() {
        StreamingDataConversionService streamingDataConversionService = mock(StreamingDataConversionService.class);
        streamingStructureConversionService = mock(StreamingStructureConversionService.class);
        StreamingAvailabilityConversionService streamingAvailabilityConversionService = mock(StreamingAvailabilityConversionService.class);
        genericRegistryAdapter = mock(GenericRegistryAdapter.class);
        QueryTranslator queryTranslator = mock(QueryTranslator.class);
        cacheService = mock(CacheService.class);
        structureFixtureService = mock(StructureFixtureService.class);
        AvailabilityFixtureService availabilityFixtureService = mock(AvailabilityFixtureService.class);
        DataFixtureService dataFixtureService = mock(DataFixtureService.class);
        LimitEmulationService limitEmulationService = mock(LimitEmulationService.class);
        SeriesLimitTruncatorProvider truncatorProvider = mock(SeriesLimitTruncatorProvider.class);
        FilterNormalizer filterNormalizer = mock(FilterNormalizer.class);
        ObjectMapper objectMapper = new ObjectMapper();

        router = new AdapterRouterImpl(
                streamingDataConversionService,
                streamingStructureConversionService,
                streamingAvailabilityConversionService,
                genericRegistryAdapter,
                queryTranslator,
                cacheService,
                structureFixtureService,
                availabilityFixtureService,
                dataFixtureService,
                limitEmulationService,
                truncatorProvider,
                filterNormalizer,
                objectMapper
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
                .registryReturnFormat(ReturnFormat.XML_2_1)
                .build();
    }
}
