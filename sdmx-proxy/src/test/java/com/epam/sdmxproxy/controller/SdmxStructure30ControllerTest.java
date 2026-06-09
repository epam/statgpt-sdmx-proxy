package com.epam.sdmxproxy.controller;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.exception.UnsupportedAgencyWildcardException;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SdmxStructure30ControllerTest {

    private static final String JSON_2_0_0 = "application/vnd.sdmx.structure+json;version=2.0.0";

    private QueryTranslator queryTranslator;
    private AdapterRouter adapterRouter;
    private ProxyConfigurationProvider configurationProvider;
    private CacheService cacheService;
    private SdmxStructure30Controller controller;

    @BeforeEach
    void setUp() {
        queryTranslator = mock(QueryTranslator.class);
        adapterRouter = mock(AdapterRouter.class);
        configurationProvider = mock(ProxyConfigurationProvider.class);
        cacheService = mock(CacheService.class);

        controller = new SdmxStructure30Controller(
                queryTranslator,
                adapterRouter,
                configurationProvider,
                cacheService
        );

        // Controller calls normalizePathSlot on every request; default the mock to the real semantics
        // (rewrite "all" -> "*", pass everything else through).
        when(queryTranslator.normalizePathSlot(anyString())).thenAnswer(inv -> {
            String slot = inv.getArgument(0);
            return "all".equals(slot) ? "*" : slot;
        });

        // ControllerUtils.logRequestUrl reads from RequestContextHolder; supply a mock request
        // so the controller's first call doesn't blow up with "No thread-bound request found".
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void wildcardAgency_toggleOff_routesToSingleAgencyTranslatorWhichThrows501() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(false));
        when(queryTranslator.translateStructureQuery(anyString(), eq("*"), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new UnsupportedAgencyWildcardException("blocked"));

        assertThatThrownBy(() -> controller.getResources(
                "dataflow", "*", "*", "*", null, "full", JSON_2_0_0, null))
                .isInstanceOf(UnsupportedAgencyWildcardException.class);

        verify(queryTranslator, never()).translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void wildcardAgency_toggleOn_cacheHit_servesCachedBytesAndSkipsTranslation() throws IOException {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        byte[] cachedBytes = "cached body".getBytes();
        when(cacheService.getReadyResponse(anyString())).thenReturn(Optional.of(cachedBytes));

        ResponseEntity<StreamingResponseBody> response = controller.getResources(
                "dataflow", "*", "*", "*", null, "full", JSON_2_0_0, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(out.toByteArray()).isEqualTo(cachedBytes);
        verify(queryTranslator, never()).translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any());
        verify(adapterRouter, never()).getStructuresWithFanOut(any(), anyString());
    }

    @Test
    void wildcardAgency_toggleOn_cacheMiss_invokesFanOutWithComputedKey() throws IOException {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        when(cacheService.getReadyResponse(anyString())).thenReturn(Optional.empty());
        List<TranslatedStructureQuery> queries = List.of(fanOutQuery("BIS"), fanOutQuery("IMF"));
        when(queryTranslator.translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(queries);
        when(adapterRouter.getStructuresWithFanOut(eq(queries), anyString()))
                .thenReturn(out -> out.write("fanned out body".getBytes()));

        ResponseEntity<StreamingResponseBody> response = controller.getResources(
                "dataflow", "*", "*", "*", null, "full", JSON_2_0_0, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(out.toString()).isEqualTo("fanned out body");
        verify(queryTranslator, times(1)).translateWildcardStructureFanOut("dataflow", "*", "*", null, "full", JSON_2_0_0);
        verify(adapterRouter, times(1)).getStructuresWithFanOut(eq(queries), anyString());
    }

    @Test
    void wildcardAgency_toggleOn_emptyQueriesReturnsEmptyBodyNoCacheWrite() throws IOException {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        when(cacheService.getReadyResponse(anyString())).thenReturn(Optional.empty());
        when(queryTranslator.translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(List.of());

        ResponseEntity<StreamingResponseBody> response = controller.getResources(
                "hierarchy", "*", "*", "*", null, "full", JSON_2_0_0, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(out.toByteArray()).isEmpty();
        verify(adapterRouter, never()).getStructuresWithFanOut(any(), anyString());
        verify(cacheService, never()).putReadyResponse(anyString(), any(byte[].class));
    }

    @Test
    void commaSeparatedAgency_toggleOn_stillRoutesToSingleAgencyTranslator() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        when(queryTranslator.translateStructureQuery(anyString(), eq("BIS,IMF"), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new UnsupportedAgencyWildcardException("blocked"));

        assertThatThrownBy(() -> controller.getResources(
                "dataflow", "BIS,IMF", "*", "*", null, "full", JSON_2_0_0, null))
                .isInstanceOf(UnsupportedAgencyWildcardException.class);

        verify(queryTranslator, never()).translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void singleAgency_toggleOn_unaffectedByFanOutBranch() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        TranslatedStructureQuery single = fanOutQuery("BIS");
        when(queryTranslator.translateStructureQuery(anyString(), eq("BIS"), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenReturn(single);
        when(adapterRouter.getStructures(single)).thenReturn(out -> {
        });

        ResponseEntity<StreamingResponseBody> response = controller.getResources(
                "dataflow", "BIS", "TEST", "1.0", null, "full", JSON_2_0_0, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(queryTranslator, times(1)).translateStructureQuery("dataflow", "BIS", "TEST", "1.0", null, "full", JSON_2_0_0, null);
        verify(queryTranslator, never()).translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void allAgency_toggleOn_engagesFanOut() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        when(cacheService.getReadyResponse(anyString())).thenReturn(Optional.empty());
        List<TranslatedStructureQuery> queries = List.of(fanOutQuery("BIS"));
        when(queryTranslator.translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(queries);
        when(adapterRouter.getStructuresWithFanOut(eq(queries), anyString())).thenReturn(out -> {
        });

        ResponseEntity<StreamingResponseBody> response = controller.getResources(
                "dataflow", "all", "*", "*", null, "full", JSON_2_0_0, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The fan-out translator received the canonical "*" path slots, not "all".
        verify(queryTranslator, times(1)).translateWildcardStructureFanOut("dataflow", "*", "*", null, "full", JSON_2_0_0);
        verify(queryTranslator, never()).translateStructureQuery(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void allAgency_toggleOff_routesToSingleAgencyTranslatorWhichThrows501() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(false));
        when(queryTranslator.translateStructureQuery(anyString(), eq("*"), anyString(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new UnsupportedAgencyWildcardException("blocked"));

        assertThatThrownBy(() -> controller.getResources(
                "dataflow", "all", "*", "*", null, "full", JSON_2_0_0, null))
                .isInstanceOf(UnsupportedAgencyWildcardException.class);

        // After normalisation the translator was called with "*", not "all".
        verify(queryTranslator, times(1)).translateStructureQuery("dataflow", "*", "*", "*", null, "full", JSON_2_0_0, null);
        verify(queryTranslator, never()).translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void allInResourceAndVersion_toggleOn_cacheKeyMatchesStarForm() {
        when(configurationProvider.getConfiguration()).thenReturn(proxyConfig(true));
        when(cacheService.getReadyResponse(anyString())).thenReturn(Optional.empty());
        List<TranslatedStructureQuery> queries = List.of(fanOutQuery("BIS"));
        when(queryTranslator.translateWildcardStructureFanOut(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(queries);
        when(adapterRouter.getStructuresWithFanOut(eq(queries), anyString())).thenReturn(out -> {
        });

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        controller.getResources("dataflow", "*", "*", "*", null, "full", JSON_2_0_0, null);
        controller.getResources("dataflow", "all", "all", "all", null, "full", JSON_2_0_0, null);

        verify(cacheService, times(2)).getReadyResponse(keyCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    private static ProxyConfiguration proxyConfig(boolean fanOutEnabled) {
        ProxyConfiguration config = new ProxyConfiguration();
        config.setStructureFanOutEnabled(fanOutEnabled);
        RegistryConfiguration bis = new RegistryConfiguration();
        bis.setName("BIS");
        config.setConfigs(List.of(bis));
        return config;
    }

    private static TranslatedStructureQuery fanOutQuery(String registryName) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName(registryName);
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry)
                .versionConfiguration(versionConfig)
                .structure(new Structure("dataflow", "*", "*", "*"))
                .references(null)
                .detail("full")
                .contentType(MediaType.parseMediaType(JSON_2_0_0))
                .registryReturnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0)
                .build();
    }
}
