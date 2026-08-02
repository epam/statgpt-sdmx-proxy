package com.epam.sdmxproxy.services.translator;

import org.springframework.stereotype.Service;

import static com.epam.sdmxproxy.services.translator.QueryTranslatorImpl.SDMX_21_ALL_WILDCARD;
import static com.epam.sdmxproxy.services.translator.QueryTranslatorImpl.SDMX_30_ALL_WILDCARD;

@Service
public class Sdmx21QueryNormalizerImpl implements Sdmx21QueryNormalizer {

    private static final String POSITION_SEPARATOR = ".";
    private static final String POSITION_SEPARATOR_REGEX = "\\.";

    @Override
    public String toKey(String key) {
        if (isAllWildcard(key)) {
            return SDMX_21_ALL_WILDCARD;
        }
        String[] positions = key.split(POSITION_SEPARATOR_REGEX, -1);
        boolean allPositionsWildcard = true;
        for (int i = 0; i < positions.length; i++) {
            if (SDMX_30_ALL_WILDCARD.equals(positions[i])) {
                positions[i] = "";
            } else if (!positions[i].isEmpty()) {
                allPositionsWildcard = false;
            }
        }
        if (allPositionsWildcard) {
            return SDMX_21_ALL_WILDCARD;
        }
        return String.join(POSITION_SEPARATOR, positions);
    }

    @Override
    public String toComponentId(String componentId) {
        if (componentId == null || componentId.isEmpty() || SDMX_30_ALL_WILDCARD.equals(componentId)) {
            return SDMX_21_ALL_WILDCARD;
        }
        return componentId;
    }

    @Override
    public String toReferences(String references) {
        if ("none".equalsIgnoreCase(references)) {
            return null;
        }
        return references;
    }

    @Override
    public String toSdmx21PathSlot(String slot) {
        if (slot == null || slot.isEmpty() || SDMX_30_ALL_WILDCARD.equals(slot)) {
            return SDMX_21_ALL_WILDCARD;
        }
        return slot;
    }

    private boolean isAllWildcard(String key) {
        return key == null || key.isEmpty() || SDMX_30_ALL_WILDCARD.equals(key) || SDMX_21_ALL_WILDCARD.equals(key);
    }
}
