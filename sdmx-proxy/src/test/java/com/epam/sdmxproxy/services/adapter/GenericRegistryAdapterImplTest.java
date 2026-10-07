package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenericRegistryAdapterImplTest {

    private SdmxApiClientProvider clientProvider;
    private Sdmx30AvailabilityClient availabilityClient;
    private GenericRegistryAdapterImpl adapter;

    @BeforeEach
    void setUp() {
        clientProvider = mock(SdmxApiClientProvider.class);
        availabilityClient = mock(Sdmx30AvailabilityClient.class);
        adapter = new GenericRegistryAdapterImpl(clientProvider, new Sdmx21QueryNormalizerImpl());

        when(clientProvider.getAvailability30Client(any())).thenReturn(availabilityClient);
        when(availabilityClient.getAvailability(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), isNull(), isNull(), isNull(), isNull())).thenReturn(new ByteArrayInputStream(new byte[0]));
    }

    /**
     * Design 041 / P4 + P5. The 2.1 data path forwarded only startPeriod and endPeriod under a
     * TODO, and formatInstant threw on every non-null Instant.
     */
    @Test
    void shouldForwardAllSdmx21DataQueryParams() {
        Sdmx21DataClient dataClient = stubDataClient();

        adapter.getData(dataQuery().toBuilder()
                .startPeriod("2015")
                .endPeriod("2020")
                .updatedAfter(Instant.parse("2024-01-01T00:00:00Z"))
                .firstNObservations(1)
                .lastNObservations(2)
                .dimensionAtObservation("TIME_PERIOD")
                .includeHistory("false")
                .attributes("none")
                .measures("none")
                .build());

        Map<String, Object> params = captureDataParams(dataClient);
        assertEquals("2015", params.get("startPeriod"));
        assertEquals("2020", params.get("endPeriod"));
        assertEquals("2024-01-01T00:00:00Z", params.get("updatedAfter"));
        assertEquals(1, params.get("firstNObservations"));
        assertEquals(2, params.get("lastNObservations"));
        assertEquals("TIME_PERIOD", params.get("dimensionAtObservation"));
        assertEquals("false", params.get("includeHistory"));
        assertEquals("serieskeysonly", params.get("detail"));
    }

    @Test
    void shouldOmitNullSdmx21DataQueryParams() {
        Sdmx21DataClient dataClient = stubDataClient();

        adapter.getData(dataQuery());

        Map<String, Object> params = captureDataParams(dataClient);
        assertTrue(params.isEmpty(), "a query with no optional parameters must send none: " + params);
    }

    /**
     * SDMX 3.0 has no data {@code detail}; the 2.1 value is folded from attributes + measures.
     */
    @Test
    void shouldFoldAttributesAndMeasuresIntoSdmx21Detail() {
        Sdmx21DataClient dataClient = stubDataClient();
        adapter.getData(dataQuery().toBuilder().attributes("dsd").measures("none").build());
        assertEquals("nodata", captureDataParams(dataClient).get("detail"));

        dataClient = stubDataClient();
        adapter.getData(dataQuery().toBuilder().attributes("none").measures("all").build());
        assertEquals("dataonly", captureDataParams(dataClient).get("detail"));

        dataClient = stubDataClient();
        adapter.getData(dataQuery().toBuilder().attributes("dsd").measures("all").build());
        assertNull(captureDataParams(dataClient).get("detail"), "full detail drops the parameter");
    }

    /**
     * Design 041 / P10. The SDMX 3.0 keyword {@code +} must reach a 2.1 registry as {@code latest}.
     */
    @Test
    void shouldMapPlusVersionToLatestInFlowRef() {
        Sdmx21DataClient dataClient = stubDataClient();

        adapter.getData(dataQuery());

        ArgumentCaptor<String> flowRef = ArgumentCaptor.forClass(String.class);
        verify(dataClient).getData(anyString(), flowRef.capture(), anyString(), anyString(), any());
        assertEquals("ESTAT,TPS00001,latest", flowRef.getValue());
    }

    private Sdmx21DataClient stubDataClient() {
        Sdmx21DataClient dataClient = mock(Sdmx21DataClient.class);
        when(clientProvider.getData21Client(any())).thenReturn(dataClient);
        when(dataClient.getData(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        return dataClient;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureDataParams(Sdmx21DataClient dataClient) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dataClient).getData(anyString(), anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private TranslatedDataQuery dataQuery() {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("ESTAT");
        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_2_1);
        return TranslatedDataQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(version)
                .agencyID("ESTAT")
                .resourceID("TPS00001")
                .version("+")
                .key("A.JAN.BE")
                .returnFormat(SdmxFormat.XML_GENERIC_DATA_2_1)
                .build();
    }

    @Test
    void getAvailability_unwrapFilterParametersFalse_wrapsFiltersWithC() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "Q");
        TranslatedAvailabilityQuery query = buildQuery(filters, false);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertEquals(List.of("Q"), capturedFilters.get("c[FREQ]"));
    }

    @Test
    void getAvailability_unwrapFilterParametersTrue_passesFiltersAsIs() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "Q");
        filters.add("L_CP_COUNTRY", "US");
        TranslatedAvailabilityQuery query = buildQuery(filters, true);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertEquals(List.of("Q"), capturedFilters.get("FREQ"));
        assertEquals(List.of("US"), capturedFilters.get("L_CP_COUNTRY"));
    }

    @Test
    void getAvailability_unwrapFilterParametersTrue_nullFilters_returnsEmptyMap() {
        TranslatedAvailabilityQuery query = buildQuery(null, true);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertTrue(capturedFilters.isEmpty());
    }

    @Test
    void getAvailability_unwrapFilterParametersTrue_emptyFilters_returnsEmptyMap() {
        TranslatedAvailabilityQuery query = buildQuery(new LinkedMultiValueMap<>(), true);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertTrue(capturedFilters.isEmpty());
    }

    @Test
    void getAvailability_unwrapFalse_multipleValuesPerComponent_commaJoinedIntoOneParam() {
        // SDMX-REST 2.2.0: c[X] may appear at most once per Component; multiple values are
        // comma-joined (OR). Repeated c[X]=A&c[X]=B is a spec violation and BIS silently
        // keeps only the first occurrence. Outbound side must always comma-join.
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.put("REF_AREA", List.of("AE", "AR", "AT"));
        filters.add("FREQ", "M");
        TranslatedAvailabilityQuery query = buildQuery(filters, false);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertEquals(List.of("AE,AR,AT"), capturedFilters.get("c[REF_AREA]"));
        assertEquals(List.of("M"), capturedFilters.get("c[FREQ]"));
    }

    @Test
    void getAvailability_unwrapTrue_multipleValuesPerComponent_commaJoinedIntoOneParam() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.put("REF_AREA", List.of("AE", "AR", "AT"));
        TranslatedAvailabilityQuery query = buildQuery(filters, true);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertEquals(List.of("AE,AR,AT"), capturedFilters.get("REF_AREA"));
    }

    @Test
    void getAvailability_nullAvailabilityConfig_fallsBackToWrapIntoC() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "Q");
        TranslatedAvailabilityQuery query = buildQueryWithNullAvailabilityConfig(filters);

        adapter.getAvailability(query);

        MultiValueMap<String, String> capturedFilters = captureFilters();
        assertEquals(List.of("Q"), capturedFilters.get("c[FREQ]"));
    }

    @SuppressWarnings("unchecked")
    private MultiValueMap<String, String> captureFilters() {
        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(availabilityClient).getAvailability(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), captor.capture(), isNull(), isNull(), isNull(), isNull());
        return captor.getValue();
    }

    private TranslatedAvailabilityQuery buildQuery(MultiValueMap<String, String> filters, boolean unwrapFilterParameters) {
        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(true);
        availabilityConfig.setUnwrapFilterParameters(unwrapFilterParameters);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_3_0);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .agencyID("TEST")
                .resourceID("TEST_FLOW")
                .version("1.0")
                .filters(filters)
                .returnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0)
                .build();
    }

    private TranslatedAvailabilityQuery buildQueryWithNullAvailabilityConfig(MultiValueMap<String, String> filters) {
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_3_0);
        versionConfig.setAvailabilityEndpointConfig(null);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .agencyID("TEST")
                .resourceID("TEST_FLOW")
                .version("1.0")
                .filters(filters)
                .returnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0)
                .build();
    }
}
