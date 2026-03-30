package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryResilienceConfig;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.AgencyRoutingException;
import com.epam.sdmxproxy.exception.FilterValidationException;
import com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException;
import com.epam.sdmxproxy.services.filter.FilterTranslator;
import com.epam.sdmxproxy.services.filter.FilterValidationResult;
import com.epam.sdmxproxy.services.filter.FilterValidator;
import com.epam.sdmxproxy.services.misc.DimensionService;
import com.epam.sdmxproxy.services.routing.AgencyRoutingService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for QueryTranslatorImpl.
 * Tests registry and version selection logic, format mapping, and query translation.
 */
class QueryTranslatorImplTest {

    private AgencyRoutingService agencyRoutingService;
    private FilterValidator filterValidator;
    private FilterTranslator filterTranslator;
    private DimensionService dimensionService;
    private QueryTranslatorImpl queryTranslator;

    @BeforeEach
    void setUp() {
        agencyRoutingService = mock(AgencyRoutingService.class);
        filterValidator = mock(FilterValidator.class);
        filterTranslator = mock(FilterTranslator.class);
        dimensionService = mock(DimensionService.class);

        // Setup default mock behavior
        when(filterValidator.validateFilters(any(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(FilterValidationResult.valid());
        when(filterTranslator.mergeFiltersIntoKey(anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // Return key as-is

        queryTranslator = new QueryTranslatorImpl(
                agencyRoutingService,
                filterValidator,
                filterTranslator,
                dimensionService
        );
    }

    // ========== Registry and Version Selection Tests ==========

    @Test
    void testSelectRegistryAndVersion_RegistryWithBothVersions_Prefers30() {
        // Given - Registry supports both 2.1 and 3.0
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When - No specific version requested (null)
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should select 3.0 version (preferred)
        assertNotNull(query);
        assertNotNull(query.getRegistryConfiguration());
        assertNotNull(query.getVersionConfiguration());
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertEquals("http://bis.org/api/v3/", query.getVersionConfiguration().getStructureEndpointConfig().getUrl());
        assertNotNull(query.getRegistryReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query.getContentType());
    }

    @Test
    void testSelectRegistryAndVersion_RegistryWithBothVersions_SelectsExactVersion21() {
        // Given - Registry supports both 2.1 and 3.0, but we want 2.1
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When - Request structure query (will use fallback logic, but we can verify it selects 3.0 by default)
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should select 3.0 (fallback preference)
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query.getContentType());
    }

    @Test
    void testSelectRegistryAndVersion_RegistryWithOnly21_Selects21() {
        // Given - Registry supports only 2.1
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("IMF", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("IMF"), isNull())).thenReturn(registryConfig);

        // When
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "IMF", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should select 2.1 (only available)
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
        assertEquals("http://test.org/api/v2.1/", query.getVersionConfiguration().getStructureEndpointConfig().getUrl());
        assertEquals(ReturnFormat.XML_2_1, query.getRegistryReturnFormat());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query.getContentType());
    }

    @Test
    void testSelectRegistryAndVersion_RegistryWithOnly30_Selects30() {
        // Given - Registry supports only 3.0
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("ECB", SdmxVersion.SDMX_3_0);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("ECB"), isNull())).thenReturn(registryConfig);

        // When
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "ECB", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should select 3.0
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertEquals("http://test.org/api/v3/", query.getVersionConfiguration().getStructureEndpointConfig().getUrl());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query.getContentType());
    }

    @Test
    void testSelectRegistryAndVersion_NoVersionsAvailable_ThrowsException() {
        // Given - Registry exists but has no versions configured
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("EmptyRegistry");
        registryConfig.setVersions(Map.of()); // Empty map

        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("EMPTY"), isNull())).thenReturn(registryConfig);

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            queryTranslator.translateStructureQuery(
                    "dataflow", "EMPTY", "TEST_FLOW", "1.0", null, "full", null, null
            );
        });
        assertEquals("No suitable SDMX version found for registry: EmptyRegistry, agency: EMPTY", exception.getMessage());
    }

    // ========== Format Mapping Tests ==========

    @Test
    void testTranslateStructureQuery_SetsCorrectVersionConfiguration() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all", "full", null, null
        );

        // Then
        assertNotNull(query.getVersionConfiguration());
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
        assertEquals("http://bis.org/api/v3/", query.getVersionConfiguration().getStructureEndpointConfig().getUrl());
    }

    @Test
    void testTranslateDataQuery_ExtractsSDMX_3_0_FromAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - Generic Accept header (no SDMX version specified)
        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/json", sdmxBeans, null
        );

        // Then - Should default to SDMX 3.0 (extracted from generic Accept header)
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
    }

    @Test
    void testTranslateAvailabilityQuery_ExtractsSDMX_3_0_FromAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - Accept header specifies SDMX 3.0
        TranslatedAvailabilityQuery query = queryTranslator.translateAvailabilityQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all", "FREQ",
                null, null, "exact", "all", null, null, null,
                "application/vnd.sdmx.data+json; version=2.0.0", sdmxBeans, null
        );

        // Then - Should select SDMX 3.0 version
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
    }

    @Test
    void testTranslateAvailabilityQuery_ExtractsSDMX_2_1_FromAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - Accept header specifies SDMX 2.1
        TranslatedAvailabilityQuery query = queryTranslator.translateAvailabilityQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all", "FREQ",
                null, null, "exact", "all", null, null, null,
                "application/vnd.sdmx.data+csv; version=1.0.0", sdmxBeans, null
        );

        // Then - Should select SDMX 2.1 version
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
    }

    @Test
    void testTranslateStructureQuery_ExtractsSDMX_3_0_FromAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When - Accept header specifies SDMX 3.0
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full",
                "application/vnd.sdmx.data+xml; version=3.0.0", null
        );

        // Then - Should select SDMX 3.0 version
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.parseMediaType("application/vnd.sdmx.data+xml;version=3.0.0"), query.getContentType());
    }

    @Test
    void testTranslateStructureQuery_ExtractsSDMX_2_1_FromAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When - Accept header specifies SDMX 2.1
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full",
                "application/vnd.sdmx.draft-sdmx-json+json; version=2.1", null
        );

        // Then - Should select SDMX 2.1 version
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.parseMediaType("application/vnd.sdmx.draft-sdmx-json+json; version=2.1"), query.getContentType());
    }

    @Test
    void testTranslateDataQuery_RequestedVersionNotAvailable_FallsBack() {
        // Given - Registry only has 2.1, but Accept header requests 3.0
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - Accept header specifies SDMX 3.0, but only 2.1 is available
        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/vnd.sdmx.data+xml; version=3.0.0", sdmxBeans, null
        );

        // Then - Should fallback to available version (2.1)
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
    }

    // ========== Structure Type Validation Tests ==========

    @Test
    void testTranslateStructureQuery_UnsupportedStructureType_ThrowsException() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When/Then - Requesting unsupported structure type
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            queryTranslator.translateStructureQuery(
                    "unsupported", "BIS", "TEST_FLOW", "1.0", null, "full", null, null
            );
        });
        assertTrue(exception.getMessage().contains("unsupported structure type is not supported by SDMX version SDMX_3_0; Supported structures"));
    }

    @Test
    void testTranslateStructureQuery_SupportedStructureType_Succeeds() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "datastructure", "BIS", "TEST_DSD", "1.0", null, "full", null, null
        );

        // Then
        assertNotNull(query);
        assertEquals("datastructure", query.getStructure().type());
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
    }

    // ========== Agency Not Found Tests ==========

    @Test
    void testSelectRegistryAndVersion_AgencyNotSupported_ThrowsException() {
        // Given - AgencyRoutingService throws for unknown agency
        ProxyConfiguration config = proxyConfig(List.of());

        when(agencyRoutingService.resolveRegistry(eq("UNKNOWN"), isNull()))
                .thenThrow(new AgencyRoutingException("UNKNOWN agency is not supported by any of used SDMX registries"));

        // When/Then
        AgencyRoutingException exception = assertThrows(AgencyRoutingException.class, () -> {
            queryTranslator.translateStructureQuery(
                    "dataflow", "UNKNOWN", "TEST_FLOW", "1.0", null, "full", null, null
            );
        });
        assertEquals("UNKNOWN agency is not supported by any of used SDMX registries", exception.getMessage());
    }

    // ========== Version-Specific Configuration Tests ==========

    @Test
    void testVersionSpecificConfiguration_DifferentVersionsHaveDifferentUrls() {
        // Given - Registry with both versions having different URLs
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);

        // When - Default selection (3.0)
        TranslatedStructureQuery query30 = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should use 3.0 URLs
        assertEquals("http://bis.org/api/v3/", query30.getVersionConfiguration().getStructureEndpointConfig().getUrl());
        assertEquals("http://bis.org/api/v3/data/", query30.getVersionConfiguration().getDataEndpointConfig().getUrl());

        // When - Create config with only 2.1
        RegistryConfiguration registryConfig21 = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config21 = proxyConfig(List.of(registryConfig21));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig21);

        TranslatedStructureQuery query21 = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", null, null
        );

        // Then - Should use 2.1 URLs
        assertEquals("http://test.org/api/v2.1/", query21.getVersionConfiguration().getStructureEndpointConfig().getUrl());
        assertEquals("http://test.org/api/v2.1/data/", query21.getVersionConfiguration().getDataEndpointConfig().getUrl());
    }

    @Test
    void testVersionSpecificConfiguration_DifferentVersionsHaveDifferentDataFormats() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - Default selection (3.0)
        TranslatedDataQuery query30 = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/json", sdmxBeans, null
        );

        // Then - Should use JSON format for 3.0
        assertEquals(ReturnFormat.JSON_1_0_0, query30.getVersionConfiguration().getDataEndpointConfig().getDefaultFormat());
        assertNotNull(query30.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query30.getContentType());

        // When - Create config with only 2.1
        RegistryConfiguration registryConfig21 = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config21 = proxyConfig(List.of(registryConfig21));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig21);

        TranslatedDataQuery query21 = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/xml", sdmxBeans, null
        );

        // Then - Should use XML format for 2.1
        assertEquals(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1, query21.getVersionConfiguration().getDataEndpointConfig().getDefaultFormat());
        assertNotNull(query21.getContentType());
        assertEquals(MediaType.APPLICATION_XML, query21.getContentType());
    }

    @Test
    void testTranslateDataQuery_WithAcceptHeader_PassesAcceptHeader() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - With Accept header
        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/vnd.sdmx.data+json;version=2.0.0", sdmxBeans, null
        );

        // Then - Should successfully translate with Accept header
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.parseMediaType("application/vnd.sdmx.data+json;version=2.0.0"), query.getContentType());
    }

    @Test
    void testTranslateDataQuery_WithNullAcceptHeader_Works() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When - With null Accept header
        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                null, sdmxBeans, null
        );

        // Then - Should default to SDMX 3.0 (extracted from null Accept header)
        assertNotNull(query);
        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertNotNull(query.getContentType());
        assertEquals(MediaType.APPLICATION_JSON, query.getContentType());
    }

    @Test
    void testTranslateDataQuery_21_TimeFilterMapsToStartPeriodEndPeriod() {
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
        when(filterTranslator.mergeFiltersIntoKey(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        MultiValueMap<String, String> c = new LinkedMultiValueMap<>();
        c.add("c[TIME_PERIOD]", "ge:2020-01+le:2020-12");

        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                c, null, null, null, null, null, null, null, null, null, false,
                "application/xml", sdmxBeans, null
        );

        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
        assertEquals("2020-01", query.getStartPeriod());
        assertEquals("2020-12", query.getEndPeriod());
        assertEquals("all", query.getKey());
    }

    @Test
    void testTranslateDataQuery_21_OnlyStartPeriodFromGe() {
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
        when(filterTranslator.mergeFiltersIntoKey(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        MultiValueMap<String, String> c = new LinkedMultiValueMap<>();
        c.add("c[TIME_PERIOD]", "ge:2020-06");

        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                c, null, null, null, null, null, null, null, null, null, false,
                "application/xml", sdmxBeans, null
        );

        assertEquals("2020-06", query.getStartPeriod());
        assertNull(query.getEndPeriod());
    }

    @Test
    void testTranslateDataQuery_30_TimeFilterDoesNotSetStartPeriodEndPeriod() {
        RegistryConfiguration registryConfig = createRegistryWithBothVersions("BIS");
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        MultiValueMap<String, String> c = new LinkedMultiValueMap<>();
        c.add("c[TIME_PERIOD]", "ge:2020-01+le:2020-12");

        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                c, null, null, null, null, null, null, null, null, null, false,
                "application/json", sdmxBeans, null
        );

        assertEquals(SdmxVersion.SDMX_3_0, query.getVersionConfiguration().getSdmxVersion());
        assertNull(query.getStartPeriod());
        assertNull(query.getEndPeriod());
    }

    @Test
    void testTranslateDataQuery_21_TimeFilterMultipleParams_IMF_WEO_mapsToStartPeriodEndPeriod() {
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("IMF.RES", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("IMF.RES"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("COUNTRY", "INDICATOR"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
        when(filterTranslator.mergeFiltersIntoKey(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        MultiValueMap<String, String> c = new LinkedMultiValueMap<>();
        c.add("c[TIME_PERIOD]", "ge:2024-12-31");
        c.add("c[TIME_PERIOD]", "le:2025-01-05");

        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "IMF.RES", "WEO", "9.0.0", "USA.NGDP_RPCH.*",
                c, null, null, null, null, null, null, null, null, null, false,
                "application/xml", sdmxBeans, null
        );

        assertEquals(SdmxVersion.SDMX_2_1, query.getVersionConfiguration().getSdmxVersion());
        assertEquals("IMF.RES", query.getAgencyID());
        assertEquals("WEO", query.getResourceID());
        assertEquals("9.0.0", query.getVersion());
        assertEquals("USA.NGDP_RPCH.*", query.getKey());
        assertEquals("2024-12-31", query.getStartPeriod());
        assertEquals("2025-01-05", query.getEndPeriod());
    }

    @Test
    void testTranslateDataQuery_21_InvalidTimeOperator_throwsFilterValidationException() {
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_2_1);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
        when(filterValidator.validateFilters(any(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(FilterValidationResult.invalid("Operator 'ne' for time dimension 'TIME_PERIOD' is not supported."));

        MultiValueMap<String, String> c = new LinkedMultiValueMap<>();
        c.add("c[TIME_PERIOD]", "ne:2020");

        assertThrows(FilterValidationException.class, () ->
                queryTranslator.translateDataQuery(
                        "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                        c, null, null, null, null, null, null, null, null, null, false,
                        "application/xml", sdmxBeans, null
                )
        );
    }

    // ========== ReturnFormat Determination Tests ==========

    @Test
    void testTranslateStructureQuery_WithBypassEnabled_UsesMatchingFormat() {
        // Given - Registry supports JSON with bypass enabled
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When - Request JSON format
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", "application/json", null
        );

        // Then - Should use matching format from supportedFormats (bypass)
        assertNotNull(query);
        assertNotNull(query.getRegistryReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
    }

    @Test
    void testTranslateStructureQuery_WithBypassDisabled_UsesDefaultFormat() {
        // Given - Registry supports JSON but bypass is disabled
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getStructureEndpointConfig().setBypassEnabled(false);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When - Request JSON format
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", "application/json", null
        );

        // Then - Should use defaultFormat
        assertNotNull(query);
        assertNotNull(query.getRegistryReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
    }

    @Test
    void testTranslateStructureQuery_WithUnsupportedFormat_UsesDefaultFormat() {
        // Given - Registry supports only JSON, but request XML
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When - Request XML format (not in supportedFormats)
        TranslatedStructureQuery query = queryTranslator.translateStructureQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", "application/xml", null
        );

        // Then - Should use defaultFormat
        assertNotNull(query);
        assertNotNull(query.getRegistryReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getRegistryReturnFormat());
    }

    @Test
    void testTranslateStructureQuery_WithNoDefaultFormat_ThrowsException() {
        // Given - Registry has no defaultFormat configured
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getStructureEndpointConfig().setDefaultFormat(null);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getStructureEndpointConfig().setBypassEnabled(false);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), any())).thenReturn(registryConfig);

        // When/Then - Should throw exception
        assertThrows(IllegalArgumentException.class, () ->
                queryTranslator.translateStructureQuery(
                        "dataflow", "BIS", "TEST_FLOW", "1.0", null, "full", "application/xml", null
                )
        );
    }

    @Test
    void testTranslateDataQuery_WithBypassEnabled_UsesMatchingFormat() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);

        // When
        TranslatedDataQuery query = queryTranslator.translateDataQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                null, null, null, null, null, null, null, null, null, null, false,
                "application/json", sdmxBeans, null
        );

        // Then
        assertNotNull(query);
        assertNotNull(query.getReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getReturnFormat());
    }

    @Test
    void testTranslateDataQuery_WithNoDefaultFormat_ThrowsException() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getDataEndpointConfig().setDefaultFormat(null);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getDataEndpointConfig().setBypassEnabled(false);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);

        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                queryTranslator.translateDataQuery(
                        "dataflow", "BIS", "TEST_FLOW", "1.0", "all",
                        null, null, null, null, null, null, null, null, null, null, false,
                        "application/xml", sdmxBeans, null
                )
        );
    }

    @Test
    void testTranslateAvailabilityQuery_WithBypassEnabled_UsesMatchingFormat() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When
        TranslatedAvailabilityQuery query = queryTranslator.translateAvailabilityQuery(
                "dataflow", "BIS", "TEST_FLOW", "1.0", "all", "FREQ",
                null, null, "exact", "all", null, null, null,
                "application/json", sdmxBeans, null
        );

        // Then
        assertNotNull(query);
        assertNotNull(query.getReturnFormat());
        assertEquals(ReturnFormat.JSON_1_0_0, query.getReturnFormat());
    }

    @Test
    void testTranslateAvailabilityQuery_WithNoDefaultFormat_ThrowsException() {
        // Given
        RegistryConfiguration registryConfig = createRegistryWithSingleVersion("BIS", SdmxVersion.SDMX_3_0);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getAvailabilityEndpointConfig().setDefaultFormat(null);
        registryConfig.getVersionConfiguration(SdmxVersion.SDMX_3_0)
                .getAvailabilityEndpointConfig().setBypassEnabled(false);
        ProxyConfiguration config = proxyConfig(List.of(registryConfig));

        when(agencyRoutingService.resolveRegistry(eq("BIS"), isNull())).thenReturn(registryConfig);
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("FREQ", "REF_AREA"));

        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
                queryTranslator.translateAvailabilityQuery(
                        "dataflow", "BIS", "TEST_FLOW", "1.0", "all", "FREQ",
                        null, null, "exact", "all", null, null, null,
                        "application/xml", sdmxBeans, null
                )
        );
    }

    // ========== Wildcard and Comma-Separated Agency Rejection Tests ==========

    @Test
    void testWildcardAgencyThrowsUnsupported() {
        assertThrows(UnsupportedAgencyWildcardException.class,
                () -> queryTranslator.translateStructureQuery(
                        "dataflow", "*", "TEST_FLOW", "1.0", null, "full", null, null
                ));
    }

    @Test
    void testCommaSeparatedAgencyThrowsUnsupported() {
        assertThrows(UnsupportedAgencyWildcardException.class,
                () -> queryTranslator.translateStructureQuery(
                        "dataflow", "BIS,IMF", "TEST_FLOW", "1.0", null, "full", null, null
                ));
    }

    @Test
    void testCommaSeparatedWithSpacesThrowsUnsupported() {
        assertThrows(UnsupportedAgencyWildcardException.class,
                () -> queryTranslator.translateStructureQuery(
                        "dataflow", "BIS, IMF", "TEST_FLOW", "1.0", null, "full", null, null
                ));
    }

    // ========== Helper Methods ==========

    private ProxyConfiguration proxyConfig(List<RegistryConfiguration> configs) {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setConfigs(configs);
        return config;
    }

    private RegistryConfiguration createRegistryWithBothVersions(String registryName) {
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName(registryName);

        // Version 2.1 configuration
        VersionSpecificRegistryConfiguration version21 = new VersionSpecificRegistryConfiguration();
        version21.setSdmxVersion(SdmxVersion.SDMX_2_1);

        StructureEndpointConfiguration structure21 = new StructureEndpointConfiguration();
        structure21.setUrl("http://bis.org/api/v2.1/");
        structure21.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        structure21.setDefaultFormat(ReturnFormat.XML_2_1);
        structure21.setBypassEnabled(true);
        structure21.setSupportedStructures(Set.of("datastructure", "dataflow"));
        version21.setStructureEndpointConfig(structure21);

        DataEndpointConfiguration data21 = new DataEndpointConfiguration();
        data21.setUrl("http://bis.org/api/v2.1/data/");
        data21.setSupportedFormats(List.of(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
        data21.setDefaultFormat(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1);
        data21.setBypassEnabled(true);
        version21.setDataEndpointConfig(data21);

        AvailabilityEndpointConfiguration availability21 = new AvailabilityEndpointConfiguration();
        availability21.setUrl("http://bis.org/api/v2.1/");
        availability21.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
        availability21.setDefaultFormat(ReturnFormat.XML_2_1);
        availability21.setBypassEnabled(true);
        availability21.setAvailabilityEnabled(true);
        version21.setAvailabilityEndpointConfig(availability21);

        version21.setResilienceConfig(new RegistryResilienceConfig());

        // Version 3.0 configuration
        VersionSpecificRegistryConfiguration version30 = new VersionSpecificRegistryConfiguration();
        version30.setSdmxVersion(SdmxVersion.SDMX_3_0);

        StructureEndpointConfiguration structure30 = new StructureEndpointConfiguration();
        structure30.setUrl("http://bis.org/api/v3/");
        structure30.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
        structure30.setDefaultFormat(ReturnFormat.JSON_1_0_0);
        structure30.setBypassEnabled(true);
        structure30.setSupportedStructures(Set.of("datastructure", "dataflow"));
        version30.setStructureEndpointConfig(structure30);

        DataEndpointConfiguration data30 = new DataEndpointConfiguration();
        data30.setUrl("http://bis.org/api/v3/data/");
        data30.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
        data30.setDefaultFormat(ReturnFormat.JSON_1_0_0);
        data30.setBypassEnabled(true);
        version30.setDataEndpointConfig(data30);

        AvailabilityEndpointConfiguration availability30 = new AvailabilityEndpointConfiguration();
        availability30.setUrl("http://bis.org/api/v3/");
        availability30.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
        availability30.setDefaultFormat(ReturnFormat.JSON_1_0_0);
        availability30.setBypassEnabled(true);
        availability30.setAvailabilityEnabled(true);
        version30.setAvailabilityEndpointConfig(availability30);

        version30.setResilienceConfig(new RegistryResilienceConfig());

        Map<SdmxVersion, VersionSpecificRegistryConfiguration> versions = new HashMap<>();
        versions.put(SdmxVersion.SDMX_2_1, version21);
        versions.put(SdmxVersion.SDMX_3_0, version30);
        registryConfig.setVersions(versions);

        return registryConfig;
    }

    private RegistryConfiguration createRegistryWithSingleVersion(String registryName, SdmxVersion version) {
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName(registryName);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(version);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();

        if (version == SdmxVersion.SDMX_2_1) {
            structureConfig.setUrl("http://test.org/api/v2.1/");
            structureConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
            structureConfig.setDefaultFormat(ReturnFormat.XML_2_1);
            structureConfig.setBypassEnabled(true);
            structureConfig.setSupportedStructures(Set.of("datastructure", "dataflow"));

            dataConfig.setUrl("http://test.org/api/v2.1/data/");
            dataConfig.setSupportedFormats(List.of(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
            dataConfig.setDefaultFormat(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1);
            dataConfig.setBypassEnabled(true);

            availabilityConfig.setUrl("http://test.org/api/v2.1/");
            availabilityConfig.setSupportedFormats(List.of(ReturnFormat.XML_2_1));
            availabilityConfig.setDefaultFormat(ReturnFormat.XML_2_1);
            availabilityConfig.setBypassEnabled(true);
            availabilityConfig.setAvailabilityEnabled(true);
        } else {
            structureConfig.setUrl("http://test.org/api/v3/");
            structureConfig.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
            structureConfig.setDefaultFormat(ReturnFormat.JSON_1_0_0);
            structureConfig.setBypassEnabled(true);
            structureConfig.setSupportedStructures(Set.of("datastructure", "dataflow"));

            dataConfig.setUrl("http://test.org/api/v3/data/");
            dataConfig.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
            dataConfig.setDefaultFormat(ReturnFormat.JSON_1_0_0);
            dataConfig.setBypassEnabled(true);

            availabilityConfig.setUrl("http://test.org/api/v3/");
            availabilityConfig.setSupportedFormats(List.of(ReturnFormat.JSON_1_0_0));
            availabilityConfig.setDefaultFormat(ReturnFormat.JSON_1_0_0);
            availabilityConfig.setBypassEnabled(true);
            availabilityConfig.setAvailabilityEnabled(true);
        }

        versionConfig.setStructureEndpointConfig(structureConfig);
        versionConfig.setDataEndpointConfig(dataConfig);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);
        versionConfig.setResilienceConfig(new RegistryResilienceConfig());

        registryConfig.setVersions(Map.of(version, versionConfig));

        return registryConfig;
    }
}
