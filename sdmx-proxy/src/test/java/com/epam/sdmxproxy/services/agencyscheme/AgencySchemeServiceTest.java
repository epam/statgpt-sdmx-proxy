package com.epam.sdmxproxy.services.agencyscheme;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgencySchemeServiceTest {

    private ProxyConfigurationProvider configurationProvider;
    private QueryTranslator queryTranslator;
    private AdapterRouter adapterRouter;
    private CacheService cacheService;
    private AgencySchemeService service;

    @BeforeEach
    void setUp() {
        configurationProvider = mock(ProxyConfigurationProvider.class);
        queryTranslator = mock(QueryTranslator.class);
        adapterRouter = mock(AdapterRouter.class);
        cacheService = mock(CacheService.class);
        when(cacheService.getReadyResponse(any())).thenReturn(Optional.empty());
        service = new AgencySchemeService(configurationProvider, queryTranslator, adapterRouter, cacheService, new ObjectMapper());
    }

    @Test
    void returnsConfiguredAgenciesWithoutSubAgencyDiscovery() throws Exception {
        ProxyConfiguration config = createConfig(
                List.of(registry("BIS", "Bank for International Settlements")),
                List.of(agency("BIS", "BIS", false))
        );
        when(configurationProvider.getConfiguration()).thenReturn(config);

        byte[] result = service.buildAgencySchemeJson();
        JsonNode root = new ObjectMapper().readTree(result);

        JsonNode agencies = root.path("data").path("agencySchemes").get(0).path("agencies");
        assertEquals(1, agencies.size());
        assertEquals("BIS", agencies.get(0).path("id").asText());
        assertEquals("Bank for International Settlements", agencies.get(0).path("name").asText());

        // No upstream query should have been made
        verify(queryTranslator, never()).translateStructureQuery(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull());
    }

    @Test
    void discoversSubAgenciesForAllowSubAgenciesRegistry() throws Exception {
        ProxyConfiguration config = createConfig(
                List.of(registry("IMF", "International Monetary Fund")),
                List.of(agency("IMF", "IMF", true))
        );
        when(configurationProvider.getConfiguration()).thenReturn(config);

        TranslatedStructureQuery mockQuery = mock(TranslatedStructureQuery.class);
        when(queryTranslator.translateStructureQueryForAgencySchemaDiscovery(eq("IMF"))).thenReturn(mockQuery);

        SdmxBeans mockBeans = mock(SdmxBeans.class);
        DataflowBean df1 = mockDataflow("IMF.STA");
        DataflowBean df2 = mockDataflow("IMF.FAD");
        DataflowBean df3 = mockDataflow("IMF");
        when(mockBeans.getDataflows()).thenReturn(Set.of(df1, df2, df3));
        when(adapterRouter.getSdmxBeans(mockQuery)).thenReturn(mockBeans);

        byte[] result = service.buildAgencySchemeJson();
        JsonNode root = new ObjectMapper().readTree(result);

        JsonNode agencies = root.path("data").path("agencySchemes").get(0).path("agencies");
        assertTrue(agencies.size() >= 3);

        boolean hasImf = false;
        boolean hasImfSta = false;
        for (JsonNode agency : agencies) {
            if ("IMF".equals(agency.path("id").asText())) {
                hasImf = true;
            }
            if ("IMF.STA".equals(agency.path("id").asText())) {
                hasImfSta = true;
            }
        }
        assertTrue(hasImf, "Should contain IMF");
        assertTrue(hasImfSta, "Should contain IMF.STA");
    }

    @Test
    void handlesUpstreamFailureGracefully() throws Exception {
        ProxyConfiguration config = createConfig(
                List.of(registry("IMF", "International Monetary Fund")),
                List.of(agency("IMF", "IMF", true))
        );
        when(configurationProvider.getConfiguration()).thenReturn(config);

        when(queryTranslator.translateStructureQuery(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenThrow(new RuntimeException("Upstream error"));

        byte[] result = service.buildAgencySchemeJson();
        JsonNode root = new ObjectMapper().readTree(result);

        JsonNode agencies = root.path("data").path("agencySchemes").get(0).path("agencies");
        assertEquals(1, agencies.size());
        assertEquals("IMF", agencies.get(0).path("id").asText());
    }

    @Test
    void responseHasCorrectStructure() throws Exception {
        ProxyConfiguration config = createConfig(
                List.of(registry("BIS", "BIS")),
                List.of(agency("BIS", "BIS", false))
        );
        when(configurationProvider.getConfiguration()).thenReturn(config);

        byte[] result = service.buildAgencySchemeJson();
        JsonNode root = new ObjectMapper().readTree(result);

        assertNotNull(root.path("data").path("agencySchemes"));
        JsonNode scheme = root.path("data").path("agencySchemes").get(0);
        assertEquals("AGENCIES", scheme.path("id").asText());
        assertEquals("SDMX_PROXY", scheme.path("agencyID").asText());
        assertEquals("1.0", scheme.path("version").asText());
    }

    private ProxyConfiguration createConfig(List<RegistryConfiguration> configs, List<AgencyConfiguration> agencies) {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(configs);
        config.setAgencies(agencies);
        return config;
    }

    private RegistryConfiguration registry(String name, String description) {
        RegistryConfiguration reg = new RegistryConfiguration();
        reg.setName(name);
        reg.setDescription(description);
        return reg;
    }

    private AgencyConfiguration agency(String name, String primaryRegistry, boolean allowSubAgencies) {
        AgencyConfiguration agency = new AgencyConfiguration();
        agency.setName(name);
        agency.setPrimaryRegistry(primaryRegistry);
        agency.setAllowSubAgencies(allowSubAgencies);
        return agency;
    }

    private DataflowBean mockDataflow(String agencyId) {
        DataflowBean df = mock(DataflowBean.class);
        when(df.getAgencyId()).thenReturn(agencyId);
        return df;
    }
}
