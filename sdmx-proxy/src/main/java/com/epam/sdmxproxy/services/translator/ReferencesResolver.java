package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.exception.UnsupportedContextException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Clamps the {@code references} query parameter to what a registry accepts.
 * <p>
 * The accepted set is scope-dependent: a registry can answer {@code descendants} for one artefact
 * and reject it, or silently ignore it, for a wildcard query over all artefacts. Eurostat does both
 * -- its 2.1 endpoint answers {@code 400 ERR_ALL_FLOWS_REFERENCES} for an all-dataflows query with
 * {@code descendants}, while its 3.0 endpoint answers 200 with the references absent.
 * <p>
 * Resolution runs inside the translator, before the cache key is built, so a downgraded response is
 * never cached under the value the client asked for.
 */
@Slf4j
@Service
public class ReferencesResolver {

    private static final String ALL_WILDCARD = "*";
    private static final String ALL_KEYWORD = "all";

    public String resolve(String requested, String resourceId, StructureEndpointConfiguration config) {
        if (config == null || requested == null) {
            return requested;
        }
        Set<String> accepted = acceptedFor(resourceId, config);
        if (accepted == null || accepted.isEmpty() || accepted.contains(requested)) {
            return requested;
        }
        Map<String, String> downgrade = config.getReferencesDowngrade();
        if (downgrade != null && downgrade.containsKey(requested)) {
            String replacement = downgrade.get(requested);
            log.debug("references={} is not accepted for resourceId={}; sending references={} instead", requested, resourceId, replacement);
            return replacement;
        }
        throw new UnsupportedContextException(String.format("references=%s is not supported by this registry for resourceId=%s; supported values: %s", requested, resourceId, accepted));
    }

    private Set<String> acceptedFor(String resourceId, StructureEndpointConfiguration config) {
        if (isWildcard(resourceId) && config.getSupportedReferencesForAll() != null) {
            return config.getSupportedReferencesForAll();
        }
        return config.getSupportedReferences();
    }

    /**
     * {@code QueryTranslator.normalizePathSlot} maps an inbound {@code all} to {@code *} before this
     * runs, so in practice only {@code *} is seen. Both spellings are matched so the resolver stays
     * correct wherever it is called from.
     */
    private boolean isWildcard(String resourceId) {
        return resourceId == null || resourceId.isEmpty() || ALL_WILDCARD.equals(resourceId) || ALL_KEYWORD.equalsIgnoreCase(resourceId);
    }
}
