package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryAdapterType;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.services.adapter.statcan.StatCanRegistryAdapter;
import com.epam.sdmxproxy.services.adapter.statcan.StatCanStructureSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoutingRegistryAdapterTest {

    private GenericRegistryAdapterImpl sdmxAdapter;
    private StatCanStructureSource statCanStructureSource;
    private RoutingRegistryAdapter router;

    @BeforeEach
    void setUp() {
        sdmxAdapter = mock(GenericRegistryAdapterImpl.class);
        statCanStructureSource = mock(StatCanStructureSource.class);
        router = new RoutingRegistryAdapter(sdmxAdapter, new StatCanRegistryAdapter(statCanStructureSource, sdmxAdapter));
    }

    @Test
    void shouldSendSdmxRegistryToGenericAdapter() {
        TranslatedStructureQuery query = structureQuery(RegistryAdapterType.SDMX);

        router.getStructures(query);

        verify(sdmxAdapter).getStructures(query);
        verify(statCanStructureSource, never()).getStructures(any(), any());
    }

    @Test
    void shouldTreatMissingAdapterAsSdmx() {
        TranslatedStructureQuery query = structureQuery(null);

        router.getStructures(query);

        verify(sdmxAdapter).getStructures(query);
    }

    @Test
    void shouldSendStatCanStructuresToStructureSource() {
        TranslatedStructureQuery query = structureQuery(RegistryAdapterType.STATCAN);

        router.getStructures(query);

        verify(statCanStructureSource).getStructures(any(), any());
        verify(sdmxAdapter, never()).getStructures(any());
    }

    @Test
    void shouldSendStatCanDataAndAvailabilityThroughGenericAdapter() {
        TranslatedDataQuery data = TranslatedDataQuery.builder().registryConfiguration(registry(RegistryAdapterType.STATCAN)).build();
        TranslatedAvailabilityQuery availability = TranslatedAvailabilityQuery.builder().registryConfiguration(registry(RegistryAdapterType.STATCAN)).build();

        router.getData(data);
        router.getAvailability(availability);

        verify(sdmxAdapter).getData(data);
        verify(sdmxAdapter).getAvailability(availability);
    }

    private static TranslatedStructureQuery structureQuery(RegistryAdapterType adapter) {
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry(adapter))
                .structure(new Structure("dataflow", "StatCan", "DF_17100005", "1.0"))
                .build();
    }

    private static RegistryConfiguration registry(RegistryAdapterType adapter) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("REG");
        registry.setAdapter(adapter);
        return registry;
    }
}
