package com.epam.sdmxproxy.services.agencyscheme;

import com.epam.sdmxproxy.configuration.data.AgencyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.registry.configuration.ProxyConfigurationProvider;
import com.epam.sdmxproxy.services.adapter.AdapterRouter;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.QueryTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds an SDMX AgencyScheme response from configured agencies + discovered sub-agencies.
 * Uses CacheService for caching; cache key includes a config version hash so the cache
 * is automatically invalidated when config changes.
 * <p>
 * Note: The response is built as manual JSON rather than via sdmx-core's mutable bean API
 * because sdmx-core's AgencyBean validation rejects dot-separated agency IDs (e.g., "IMF.STA")
 * which are valid in the SDMX standard as nested agency paths. A proper fix would require
 * building hierarchical agency nesting, which is deferred to a follow-up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencySchemeService {

    private static final String CACHE_KEY_PREFIX = "agencyscheme:";

    private final ProxyConfigurationProvider configurationProvider;
    private final QueryTranslator queryTranslator;
    private final AdapterRouter adapterRouter;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public Optional<byte[]> getCachedResponse(MediaType mediaType) {
        String cacheKey = buildCacheKey(mediaType);
        return cacheService.getReadyResponse(cacheKey);
    }

    public void cacheResponse(MediaType mediaType, byte[] response) {
        String cacheKey = buildCacheKey(mediaType);
        cacheService.putReadyResponse(cacheKey, response);
    }

    public byte[] buildAgencySchemeJson() {
        ProxyConfiguration config = configurationProvider.getConfiguration();
        List<AgencyConfiguration> agencies = config.getAgencies();

        Set<AgencyEntry> allAgencies = new LinkedHashSet<>();

        for (AgencyConfiguration agencyConfig : agencies) {
            String agencyName = agencyConfig.getName();
            String description = findRegistryDescription(agencyName, config.getConfigs());
            allAgencies.add(new AgencyEntry(agencyName, description != null ? description : agencyName));

            if (agencyConfig.isAllowSubAgencies()) {
                Set<String> subAgencies = discoverSubAgencies(agencyConfig);
                for (String subAgency : subAgencies) {
                    if (!subAgency.equals(agencyName)) {
                        allAgencies.add(new AgencyEntry(subAgency, subAgency));
                    }
                }
            }
        }

        return serializeAgencyScheme(allAgencies);
    }

    private Set<String> discoverSubAgencies(AgencyConfiguration agencyConfig) {
        Set<String> subAgencies = new LinkedHashSet<>();
        try {
            var structureQuery = queryTranslator.translateStructureQuery("dataflow", agencyConfig.getName(), "*", "~", "none", "allstubs", null, null);
            SdmxBeans beans = adapterRouter.getSdmxBeans(structureQuery);
            for (DataflowBean dataflow : beans.getDataflows()) {
                String maintainerAgency = dataflow.getAgencyId();
                if (maintainerAgency != null && !maintainerAgency.isBlank()) {
                    subAgencies.add(maintainerAgency);
                }
            }
            log.debug("Discovered {} sub-agencies for {}: {}", subAgencies.size(), agencyConfig.getName(), subAgencies);
        } catch (Exception e) {
            log.warn("Failed to discover sub-agencies for {}: {}. Returning configured agencies only.", agencyConfig.getName(), e.getMessage());
        }
        return subAgencies;
    }

    private String findRegistryDescription(String agencyName, List<RegistryConfiguration> configs) {
        for (RegistryConfiguration registry : configs) {
            if (agencyName.equals(registry.getName())) {
                return registry.getDescription();
            }
        }
        return null;
    }

    private byte[] serializeAgencyScheme(Set<AgencyEntry> agencies) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode data = root.putObject("data");
            ArrayNode agencySchemes = data.putArray("agencySchemes");

            ObjectNode scheme = agencySchemes.addObject();
            scheme.put("id", "AGENCIES");
            scheme.put("agencyID", "SDMX_PROXY");
            scheme.put("name", "SDMX Proxy Configured Agencies");
            scheme.put("version", "1.0");

            ArrayNode agenciesArray = scheme.putArray("agencies");
            for (AgencyEntry agency : agencies) {
                ObjectNode agencyNode = agenciesArray.addObject();
                agencyNode.put("id", agency.id());
                agencyNode.put("name", agency.name());
            }

            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize AgencyScheme", e);
        }
    }

    private String buildCacheKey(MediaType mediaType) {
        int configVersion = configurationProvider.getConfiguration().getAgencies().hashCode();
        return CACHE_KEY_PREFIX + configVersion + ":" + mediaType;
    }

    private record AgencyEntry(String id, String name) {
    }
}
