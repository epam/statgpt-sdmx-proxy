package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        TranslatedStructureQuery query = buildStructureQuery();
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

    private static TranslatedStructureQuery buildStructureQuery() {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("TEST_REG");
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .structure(new Structure("dataflow", "TEST_AGENCY", "TEST_FLOW", "1.0"))
                .references("descendants")
                .detail("full")
                .registryReturnFormat(ReturnFormat.XML_2_1)
                .build();
    }
}
