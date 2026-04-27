package com.epam.sdmxproxy.services.limit;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KeyParserImpl implements KeyParser {

    static final String POSITION_SEPARATOR = ".";
    static final String VALUE_SEPARATOR = "+";
    static final String WILDCARD = "*";
    static final String ALL_KEYWORD = "all";

    @Override
    public Map<String, List<String>> parseKey(String key, List<String> nonTimeDimensionIds) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String dim : nonTimeDimensionIds) {
            out.put(dim, List.of());
        }
        if (key == null || key.isEmpty() || WILDCARD.equals(key) || ALL_KEYWORD.equals(key)) {
            return out;
        }
        String[] parts = key.split("\\.", -1);
        int limit = Math.min(parts.length, nonTimeDimensionIds.size());
        for (int i = 0; i < limit; i++) {
            String part = parts[i];
            if (part.isEmpty() || WILDCARD.equals(part)) {
                continue;
            }
            String[] values = part.split("\\+");
            List<String> list = new ArrayList<>(values.length);
            for (String v : values) {
                if (!v.isEmpty()) {
                    list.add(v);
                }
            }
            out.put(nonTimeDimensionIds.get(i), list);
        }
        return out;
    }

    @Override
    public String buildKey(
            Map<String, List<String>> perDim,
            List<String> nonTimeDimensionIds,
            boolean mergeAllWildcard
    ) {
        boolean allWildcard = true;
        List<String> positions = new ArrayList<>(nonTimeDimensionIds.size());
        for (String dim : nonTimeDimensionIds) {
            List<String> values = perDim.getOrDefault(dim, List.of());
            if (values.isEmpty()) {
                positions.add(WILDCARD);
            } else {
                allWildcard = false;
                positions.add(String.join(VALUE_SEPARATOR, values));
            }
        }
        if (allWildcard && mergeAllWildcard) {
            return WILDCARD;
        }
        return String.join(POSITION_SEPARATOR, positions);
    }
}
