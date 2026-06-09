package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        adapter = new GenericRegistryAdapterImpl(clientProvider);

        when(clientProvider.getAvailability30Client(any())).thenReturn(availabilityClient);
        when(availabilityClient.getAvailability(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), isNull(), isNull(), isNull(), isNull())).thenReturn(new ByteArrayInputStream(new byte[0]));
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
