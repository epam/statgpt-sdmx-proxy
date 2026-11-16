package com.epam.sdmxproxy.registry.api;

import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.registry.api.client.Sdmx21AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx21StructureClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30AvailabilityClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30DataClient;
import com.epam.sdmxproxy.registry.api.client.Sdmx30StructureClient;
import com.epam.sdmxproxy.registry.api.client.SdmxApiBase;

public interface SdmxApiClientProvider {

    Sdmx21AvailabilityClient getAvailability21Client(RegistrySelectionResult selectedRegistry);

    Sdmx30AvailabilityClient getAvailability30Client(RegistrySelectionResult selectedRegistry);

    Sdmx21DataClient getData21Client(RegistrySelectionResult selectedRegistry);

    Sdmx30DataClient getData30Client(RegistrySelectionResult selectedRegistry);

    Sdmx21StructureClient getStructure21Client(RegistrySelectionResult selectedRegistry);

    Sdmx30StructureClient getStructure30Client(RegistrySelectionResult selectedRegistry);

    /**
     * A client of any Feign interface at {@code baseUrl}, built and cached like the SDMX clients, so
     * it shares the registry's circuit breaker, retry, rate limit, and timeouts. For registry
     * adapters that speak a non-SDMX API next to SDMX (Statistics Canada, design 043).
     */
    <T extends SdmxApiBase> T getClient(Class<T> clientClass, String baseUrl, RegistrySelectionResult selectedRegistry);
}
