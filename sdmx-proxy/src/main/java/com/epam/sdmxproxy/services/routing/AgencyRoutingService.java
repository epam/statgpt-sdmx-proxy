package com.epam.sdmxproxy.services.routing;

import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;

public interface AgencyRoutingService {
    RegistryConfiguration resolveRegistry(String requestedAgency, String sourceArtefactUrn);
}
