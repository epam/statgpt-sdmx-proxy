package com.epam.sdmxproxy.services.routing;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.exception.AgencyRoutingException;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgencyRoutingServiceImplTest {

    private ProxyConfigurationProvider configurationProvider;
    private AgencyRoutingServiceImpl routingService;

    private RegistryConfiguration bisRegistry;
    private RegistryConfiguration imfRegistry;

    @BeforeEach
    void setUp() {
        configurationProvider = mock(ProxyConfigurationProvider.class);
        routingService = new AgencyRoutingServiceImpl(configurationProvider);

        bisRegistry = new RegistryConfiguration();
        bisRegistry.setName("BIS");

        imfRegistry = new RegistryConfiguration();
        imfRegistry.setName("IMF");
    }

    private AgencyConfiguration agencyConfig(String name, String primaryRegistry, boolean allowSubAgencies) {
        AgencyConfiguration agency = new AgencyConfiguration();
        agency.setName(name);
        agency.setPrimaryRegistry(primaryRegistry);
        agency.setAllowSubAgencies(allowSubAgencies);
        return agency;
    }

    private ProxyConfiguration proxyConfig(List<RegistryConfiguration> configs, List<AgencyConfiguration> agencies) {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(configs);
        config.setAgencies(agencies);
        return config;
    }

    @Nested
    class UrnParsing {

        @Test
        void extractsAgencyFromCodelistUrn() {
            String agency = AgencyRoutingServiceImpl.extractAgencyFromUrn(
                    "urn:sdmx:org.sdmx.infomodel.codelist.Codelist=BIS:CL_FREQ(1.0)");
            assertEquals("BIS", agency);
        }

        @Test
        void extractsAgencyFromDsdUrn() {
            String agency = AgencyRoutingServiceImpl.extractAgencyFromUrn(
                    "urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=IMF.STA:BOP_DIR_TRAD(1.0)");
            assertEquals("IMF.STA", agency);
        }

        @Test
        void extractsAgencyFromDataflowUrn() {
            String agency = AgencyRoutingServiceImpl.extractAgencyFromUrn(
                    "urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=BIS:WS_CBS_PUB(1.0)");
            assertEquals("BIS", agency);
        }

        @Test
        void returnsNullForNull() {
            assertNull(AgencyRoutingServiceImpl.extractAgencyFromUrn(null));
        }

        @Test
        void returnsNullForBlank() {
            assertNull(AgencyRoutingServiceImpl.extractAgencyFromUrn("  "));
        }

        @Test
        void returnsNullForMalformedUrn() {
            assertNull(AgencyRoutingServiceImpl.extractAgencyFromUrn("not-a-urn"));
        }

        @Test
        void returnsNullForPartialUrn() {
            assertNull(AgencyRoutingServiceImpl.extractAgencyFromUrn("urn:sdmx:org.sdmx.infomodel"));
        }
    }

    @Nested
    class ExactMatchRouting {

        @Test
        void routesToPrimaryRegistry() {
            AgencyConfiguration agency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), List.of(agency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("BIS", null);
            assertEquals("BIS", result.getName());
        }

        @Test
        void routesImfToPrimaryRegistry() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry, imfRegistry), List.of(bisAgency, imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("IMF", null);
            assertEquals("IMF", result.getName());
        }

        @Test
        void exactMatchTakesPriorityOverSubAgencyMatch() {
            // Both IMF (with allowSubAgencies) and IMF.STA (exact) are configured
            RegistryConfiguration specialRegistry = new RegistryConfiguration();
            specialRegistry.setName("SPECIAL");

            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            AgencyConfiguration imfStaAgency = agencyConfig("IMF.STA", "SPECIAL", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(imfRegistry, specialRegistry),
                    List.of(imfAgency, imfStaAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("IMF.STA", null);
            assertEquals("SPECIAL", result.getName());
        }

        @Test
        void throwsWhenAgencyNotInConfig() {
            AgencyConfiguration agency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), List.of(agency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            AgencyRoutingException ex = assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("UNKNOWN", null));
            assertTrue(ex.getMessage().contains("UNKNOWN"));
        }

        @Test
        void throwsWhenNoAgenciesConfigured() {
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), List.of());
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("BIS", null));
        }

        @Test
        void throwsWhenAgenciesNull() {
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), null);
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("BIS", null));
        }

        @Test
        void throwsWhenPrimaryRegistryNotFoundInConfigs() {
            AgencyConfiguration agency = agencyConfig("BIS", "NONEXISTENT", false);
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), List.of(agency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("BIS", null));
        }
    }

    @Nested
    class SubAgencyRouting {

        @Test
        void routesSubAgencyViaAllowSubAgencies() {
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(List.of(imfRegistry), List.of(imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("IMF.STA", null);
            assertEquals("IMF", result.getName());
        }

        @Test
        void routesDeeplyNestedSubAgency() {
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(List.of(imfRegistry), List.of(imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("IMF.STA.DS", null);
            assertEquals("IMF", result.getName());
        }

        @Test
        void doesNotMatchWithoutDotSeparator() {
            // "IMFSTA" should NOT match "IMF" (no dot separator)
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(List.of(imfRegistry), List.of(imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("IMFSTA", null));
        }

        @Test
        void doesNotMatchWhenAllowSubAgenciesIsFalse() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(List.of(bisRegistry), List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("BIS.SUB", null));
        }

        @Test
        void longestPrefixWins() {
            // Both IMF and IMF.STA have allowSubAgencies=true
            // Request for IMF.STA.DS should match IMF.STA (longer prefix)
            RegistryConfiguration imfStaRegistry = new RegistryConfiguration();
            imfStaRegistry.setName("IMF_STA_REG");

            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            AgencyConfiguration imfStaAgency = agencyConfig("IMF.STA", "IMF_STA_REG", true);
            ProxyConfiguration config = proxyConfig(
                    List.of(imfRegistry, imfStaRegistry),
                    List.of(imfAgency, imfStaAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("IMF.STA.DS", null);
            assertEquals("IMF_STA_REG", result.getName());
        }

        @Test
        void unknownAgencyNotMatchedBySubAgency() {
            // ESTAT is not configured, and no allowSubAgencies matches it
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry, imfRegistry),
                    List.of(bisAgency, imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("ESTAT", null));
        }
    }

    @Nested
    class CrossReferenceRouting {

        private static final String BIS_DATAFLOW_URN =
                "urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=BIS:WS_CBS_PUB(1.0)";
        private static final String IMF_DSD_URN =
                "urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=IMF:BOP_DIR_TRAD(1.0)";

        @Test
        void headerAgencyDeterminesRegistry() {
            // Request for ESTAT codelist with BIS source URN -> routes to BIS
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry, imfRegistry),
                    List.of(bisAgency, imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("ESTAT", BIS_DATAFLOW_URN);
            assertEquals("BIS", result.getName());
        }

        @Test
        void headerAgencyRoutesRegardlessOfPathAgency() {
            // Path agency is BIS, but source URN points to IMF -> routes to IMF
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry, imfRegistry),
                    List.of(bisAgency, imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("BIS", IMF_DSD_URN);
            assertEquals("IMF", result.getName());
        }

        @Test
        void fallsThroughWhenSourceAgencyNotInConfig() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry),
                    List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            // Source agency UNKNOWN falls through to Path 2 -> BIS
            RegistryConfiguration result = routingService.resolveRegistry(
                    "BIS", "urn:sdmx:org.sdmx.infomodel.codelist.Codelist=UNKNOWN:CL_TEST(1.0)");
            assertEquals("BIS", result.getName());
        }

        @Test
        void fallsThroughWhenUrnMalformed() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry),
                    List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("BIS", "not-a-valid-urn");
            assertEquals("BIS", result.getName());
        }

        @Test
        void fallsThroughWhenBlankUrn() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry),
                    List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("BIS", "   ");
            assertEquals("BIS", result.getName());
        }

        @Test
        void routesToSourceAgencyEvenWhenPathAgencyUnknown() {
            // Source URN points to BIS (known agency) -- routes there regardless of path agency
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry),
                    List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            RegistryConfiguration result = routingService.resolveRegistry("UNKNOWN", BIS_DATAFLOW_URN);
            assertEquals("BIS", result.getName());
        }

        @Test
        void throwsWhenBothSourceAndPathAgencyUnknown() {
            AgencyConfiguration bisAgency = agencyConfig("BIS", "BIS", false);
            ProxyConfiguration config = proxyConfig(
                    List.of(bisRegistry),
                    List.of(bisAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            // Source URN points to UNKNOWN agency, falls through to Path 2 which also fails
            assertThrows(AgencyRoutingException.class,
                    () -> routingService.resolveRegistry("UNKNOWN",
                            "urn:sdmx:org.sdmx.infomodel.codelist.Codelist=UNKNOWN:CL_TEST(1.0)"));
        }

        @Test
        void sourceAgencyResolvedViaSubAgencyMatch() {
            // Source URN points to IMF.STA, which resolves via IMF's allowSubAgencies
            AgencyConfiguration imfAgency = agencyConfig("IMF", "IMF", true);
            ProxyConfiguration config = proxyConfig(
                    List.of(imfRegistry),
                    List.of(imfAgency));
            when(configurationProvider.getConfiguration()).thenReturn(config);

            String imfStaUrn = "urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=IMF.STA:BOP_DIR_TRAD(1.0)";
            RegistryConfiguration result = routingService.resolveRegistry("ANYTHING", imfStaUrn);
            assertEquals("IMF", result.getName());
        }
    }
}
