package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the SDMX 2.1 outbound path: the proxy speaks SDMX 3.0 to its clients, so every 3.0 wildcard
 * has to be rewritten into 2.1 grammar before it reaches an older registry.
 */
class GenericRegistryAdapter21Test {

    private Sdmx21DataClient dataClient;
    private Sdmx21AvailabilityClient availabilityClient;
    private Sdmx21StructureClient structureClient;
    private GenericRegistryAdapterImpl adapter;

    @BeforeEach
    void setUp() {
        SdmxApiClientProvider clientProvider = mock(SdmxApiClientProvider.class);
        dataClient = mock(Sdmx21DataClient.class);
        availabilityClient = mock(Sdmx21AvailabilityClient.class);
        structureClient = mock(Sdmx21StructureClient.class);
        adapter = new GenericRegistryAdapterImpl(clientProvider, new Sdmx21QueryNormalizerImpl());

        when(clientProvider.getData21Client(any())).thenReturn(dataClient);
        when(clientProvider.getAvailability21Client(any())).thenReturn(availabilityClient);
        when(clientProvider.getStructure21Client(any())).thenReturn(structureClient);
        when(structureClient.getStructures(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(dataClient.getData(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(availabilityClient.getAvailability(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull(), anyString(), isNull())).thenReturn(new ByteArrayInputStream(new byte[0]));
    }

    @Test
    void getData21_rewritesPerPositionWildcardsAsEmptyPositions() {
        adapter.getData(dataQuery("USA.A.*.*.*", "1.0"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataClient).getData(anyString(), anyString(), keyCaptor.capture(), anyString(), any());
        assertThat(keyCaptor.getValue()).isEqualTo("USA.A...");
    }

    @Test
    void getData21_rewritesWholeKeyWildcardAsAll() {
        adapter.getData(dataQuery("*", "1.0"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataClient).getData(anyString(), anyString(), keyCaptor.capture(), anyString(), any());
        assertThat(keyCaptor.getValue()).isEqualTo("all");
    }

    @Test
    void getData21_rewritesWildcardVersionSlotInFlowRef() {
        adapter.getData(dataQuery("USA.A.*.*.*", "*"));

        ArgumentCaptor<String> flowRefCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataClient).getData(anyString(), flowRefCaptor.capture(), anyString(), anyString(), any());
        assertThat(flowRefCaptor.getValue()).isEqualTo("OECD.ENV.EPI,DF_TEST,all");
    }

    @Test
    void getAvailability21_rewritesKeyComponentIdAndDropsNoneReferences() {
        adapter.getAvailability(availabilityQuery("*", "*", "none"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> componentCaptor = ArgumentCaptor.forClass(String.class);
        verify(availabilityClient).getAvailability(anyString(), anyString(), keyCaptor.capture(), anyString(), componentCaptor.capture(), isNull(), isNull(), isNull(), anyString(), isNull());
        assertThat(keyCaptor.getValue()).isEqualTo("all");
        assertThat(componentCaptor.getValue()).isEqualTo("all");
    }

    @Test
    void getAvailability21_keepsExplicitComponentIdAndReferences() {
        when(availabilityClient.getAvailability(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull(), anyString(), anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));

        adapter.getAvailability(availabilityQuery("USA.A.*.*.*", "REF_AREA", "all"));

        ArgumentCaptor<String> componentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> referencesCaptor = ArgumentCaptor.forClass(String.class);
        verify(availabilityClient).getAvailability(anyString(), anyString(), anyString(), anyString(), componentCaptor.capture(), isNull(), isNull(), isNull(), anyString(), referencesCaptor.capture());
        assertThat(componentCaptor.getValue()).isEqualTo("REF_AREA");
        assertThat(referencesCaptor.getValue()).isEqualTo("all");
    }

    @Test
    void getStructures21_rewritesWildcardAgencySlotAsAll() {
        // Agency-scheme discovery sends a literal '*' agency; only the id and version slots are
        // pre-mapped by the translator, so the adapter has to cover the agency slot itself.
        adapter.getStructures(structureQuery("*", "all", "all"));

        ArgumentCaptor<String> agencyCaptor = ArgumentCaptor.forClass(String.class);
        verify(structureClient).getStructures(anyString(), anyString(), agencyCaptor.capture(), anyString(), anyString(), anyString(), anyString());
        assertThat(agencyCaptor.getValue()).isEqualTo("all");
    }

    @Test
    void getStructures21_keepsConcreteSlotsUntouched() {
        adapter.getStructures(structureQuery("OECD.ENV.EPI", "DF_TEST", "1.0"));

        ArgumentCaptor<String> agencyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> versionCaptor = ArgumentCaptor.forClass(String.class);
        verify(structureClient).getStructures(anyString(), anyString(), agencyCaptor.capture(), idCaptor.capture(), versionCaptor.capture(), anyString(), anyString());
        assertThat(agencyCaptor.getValue()).isEqualTo("OECD.ENV.EPI");
        assertThat(idCaptor.getValue()).isEqualTo("DF_TEST");
        assertThat(versionCaptor.getValue()).isEqualTo("1.0");
    }

    @Test
    void getStructures21_rewritesEveryWildcardSlotAsAll() {
        adapter.getStructures(structureQuery("*", "*", "*"));

        ArgumentCaptor<String> agencyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> versionCaptor = ArgumentCaptor.forClass(String.class);
        verify(structureClient).getStructures(anyString(), anyString(), agencyCaptor.capture(), idCaptor.capture(), versionCaptor.capture(), anyString(), anyString());
        assertThat(agencyCaptor.getValue()).isEqualTo("all");
        assertThat(idCaptor.getValue()).isEqualTo("all");
        assertThat(versionCaptor.getValue()).isEqualTo("all");
    }

    private TranslatedStructureQuery structureQuery(String agency, String id, String version) {
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registryConfig())
                .versionConfiguration(versionConfig())
                .structure(new Structure("dataflow", agency, id, version))
                .references("children")
                .detail("full")
                .contentType(MediaType.parseMediaType(SdmxFormat.XML_STRUCTURE_2_1.getContentType()))
                .registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private TranslatedDataQuery dataQuery(String key, String version) {
        return TranslatedDataQuery.builder()
                .registryConfiguration(registryConfig())
                .versionConfiguration(versionConfig())
                .agencyID("OECD.ENV.EPI")
                .resourceID("DF_TEST")
                .version(version)
                .key(key)
                .contentType(MediaType.parseMediaType(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1.getContentType()))
                .returnFormat(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1)
                .build();
    }

    private TranslatedAvailabilityQuery availabilityQuery(String key, String componentId, String references) {
        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig())
                .versionConfiguration(versionConfig())
                .agencyID("OECD.ENV.EPI")
                .resourceID("DF_TEST")
                .version("1.0")
                .key(key)
                .componentId(componentId)
                .mode("exact")
                .references(references)
                .returnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private RegistryConfiguration registryConfig() {
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("OECD");
        return registryConfig;
    }

    private VersionSpecificRegistryConfiguration versionConfig() {
        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(true);
        availabilityConfig.setDefaultFormat(SdmxFormat.XML_STRUCTURE_2_1);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);
        versionConfig.setDataEndpointConfig(dataConfig);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);
        return versionConfig;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyQueryMap() {
        return Map.of();
    }
}
