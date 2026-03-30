package com.epam.sdmxproxy.configserver.service;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ConfigValidator {

    public void validate(ProxyConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        List<RegistryConfiguration> configs = configuration.getConfigs();
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("At least one registry must be defined in 'configs'");
        }

        // Check for duplicate registry names
        Set<String> registryNames = new HashSet<>();
        for (RegistryConfiguration registry : configs) {
            if (registry.getName() == null || registry.getName().isBlank()) {
                throw new IllegalArgumentException("Registry name must not be null or blank");
            }
            if (!registryNames.add(registry.getName())) {
                throw new IllegalArgumentException("Duplicate registry name: " + registry.getName());
            }
            if (registry.getVersions() == null || registry.getVersions().isEmpty()) {
                throw new IllegalArgumentException("Registry '" + registry.getName() + "' must have at least one version configured");
            }
        }

        List<AgencyConfiguration> agencies = configuration.getAgencies();
        if (agencies == null || agencies.isEmpty()) {
            throw new IllegalArgumentException("At least one agency must be defined in 'agencies'");
        }

        Set<String> validRegistryNames = configs.stream().map(RegistryConfiguration::getName).collect(Collectors.toSet());

        for (AgencyConfiguration agency : agencies) {
            if (agency.getName() == null || agency.getName().isBlank()) {
                throw new IllegalArgumentException("Agency name must not be null or blank");
            }
            if (agency.getPrimaryRegistry() == null || agency.getPrimaryRegistry().isBlank()) {
                throw new IllegalArgumentException("Agency '" + agency.getName() + "' must have a primaryRegistry");
            }
            if (!validRegistryNames.contains(agency.getPrimaryRegistry())) {
                throw new IllegalArgumentException("Agency '" + agency.getName() + "' references unknown registry '" + agency.getPrimaryRegistry() + "'. Available registries: " + validRegistryNames);
            }
        }
    }
}
