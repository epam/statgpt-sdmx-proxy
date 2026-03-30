package com.epam.sdmxproxy.services.routing;

import com.epam.jsdmx.infomodel.sdmx30.SdmxUrn;
import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.exception.AgencyRoutingException;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyRoutingServiceImpl implements AgencyRoutingService {

    private final ProxyConfigurationProvider configurationProvider;

    static String extractAgencyFromUrn(String urn) {
        if (urn == null || urn.isBlank()) {
            return null;
        }
        try {
            return SdmxUrn.getUrnComponents(urn).getAgency();
        } catch (SdmxUrn.UrnFormatException e) {
            return null;
        }
    }

    @Override
    public RegistryConfiguration resolveRegistry(String requestedAgency, String sourceArtefactUrn) {
        ProxyConfiguration configuration = configurationProvider.getConfiguration();
        List<RegistryConfiguration> configs = configuration.getConfigs();
        List<AgencyConfiguration> agencies = configuration.getAgencies();

        if (agencies == null || agencies.isEmpty()) {
            throw new AgencyRoutingException(String.format("%s agency is not supported: no agencies configured", requestedAgency));
        }

        // First path: resolve from source agency (only when header is present)
        if (sourceArtefactUrn != null && !sourceArtefactUrn.isBlank()) {
            RegistryConfiguration sourceResult = resolveFromSourceAgency(sourceArtefactUrn, agencies, configs);
            if (sourceResult != null) {
                return sourceResult;
            }
        }

        // Second path: standard routing by requested agency
        return resolveStandard(requestedAgency, agencies, configs);
    }

    /**
     * First path: source agency routing.
     * Extract agency from URN, find its config, return its primaryRegistry.
     * No cross-check against the requested agency's registry set.
     */
    private RegistryConfiguration resolveFromSourceAgency(
            String sourceArtefactUrn,
            List<AgencyConfiguration> agencies,
            List<RegistryConfiguration> configs
    ) {
        String sourceAgency = extractAgencyFromUrn(sourceArtefactUrn);
        if (sourceAgency == null) {
            return null;
        }

        AgencyConfiguration sourceAgencyConfig = findAgencyConfig(sourceAgency, agencies);
        if (sourceAgencyConfig == null) {
            log.debug("Source agency '{}' from URN not found in agencies config, falling through", sourceAgency);
            return null;
        }

        if (sourceAgencyConfig.getPrimaryRegistry() == null) {
            log.debug("Source agency '{}' has no primaryRegistry, falling through", sourceAgency);
            return null;
        }

        RegistryConfiguration registry = findRegistryByName(sourceAgencyConfig.getPrimaryRegistry(), configs);
        if (registry == null) {
            log.debug("Could not resolve registry '{}' for source agency '{}', falling through", sourceAgencyConfig.getPrimaryRegistry(), sourceAgency);
            return null;
        }

        log.debug("Source agency match: source '{}' -> registry '{}'", sourceAgency, registry.getName());
        return registry;
    }

    /**
     * Second path: standard routing from requested agency.
     * Find config, return primaryRegistry.
     */
    private RegistryConfiguration resolveStandard(
            String requestedAgency,
            List<AgencyConfiguration> agencies,
            List<RegistryConfiguration> configs
    ) {
        AgencyConfiguration agencyConfig = findAgencyConfig(requestedAgency, agencies);
        if (agencyConfig == null) {
            throw new AgencyRoutingException(String.format("%s agency is not supported by any of used SDMX registries", requestedAgency));
        }

        if (agencyConfig.getPrimaryRegistry() == null) {
            throw new AgencyRoutingException(String.format("%s agency has no primary registry configured", requestedAgency));
        }

        RegistryConfiguration registry = findRegistryByName(agencyConfig.getPrimaryRegistry(), configs);
        if (registry != null) {
            log.debug("Standard routing: agency '{}' -> registry '{}'", requestedAgency, registry.getName());
            return registry;
        }

        throw new AgencyRoutingException(String.format("%s agency is not supported by any of used SDMX registries", requestedAgency));
    }

    private RegistryConfiguration findRegistryByName(String name, List<RegistryConfiguration> configs) {
        for (RegistryConfiguration config : configs) {
            if (name.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }

    /**
     * Two-step agency lookup:
     * 1. Exact match (highest priority)
     * 2. Sub-agency match: iterate agencies with allowSubAgencies=true,
     * check startsWith(name + "."), longest prefix wins
     */
    private AgencyConfiguration findAgencyConfig(String agencyId, List<AgencyConfiguration> agencies) {
        // 1. Exact match (highest priority)
        for (AgencyConfiguration agency : agencies) {
            if (agencyId.equals(agency.getName())) {
                return agency;
            }
        }

        // 2. Sub-agency match: longest prefix wins
        AgencyConfiguration bestMatch = null;
        for (AgencyConfiguration agency : agencies) {
            if (agency.isAllowSubAgencies() && agencyId.startsWith(agency.getName() + ".")) {
                if (bestMatch == null || agency.getName().length() > bestMatch.getName().length()) {
                    bestMatch = agency;
                }
            }
        }

        return bestMatch;
    }
}
