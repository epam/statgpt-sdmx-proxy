package com.epam.sdmxproxy.services.filter;

import com.epam.sdmxproxy.services.limit.KeyParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a (key, filters) pair so that every per-dimension constraint lives in
 * {@code c[]} and the path key carries no positional narrowing. The normalized form
 * is the only outbound shape that BIS / FusionRegistry handles correctly on both
 * {@code /availability} and {@code /data} -- see design 016.
 * <p>
 * The transformation:
 * <ol>
 *   <li>Parse the positional key into per-dim values via {@link KeyParser}.</li>
 *   <li>For each dim with non-empty values, contribute a {@code c[<dim>]=v1,v2,...}
 *       entry to the output filter map.</li>
 *   <li>Existing filter entries pass through; when the same dim appears in both the
 *       key and the existing filters, values are unioned (de-duplicated, preserving
 *       order).</li>
 *   <li>Emit {@code "*"} as the new path key.</li>
 * </ol>
 * <p>
 * Operator-style entries already in {@code filters} (e.g.
 * {@code TIME_PERIOD -> [ge:2021-04-01+le:2026-04-30]}) flow through unchanged.
 */
@Component
@RequiredArgsConstructor
public class FilterNormalizer {

    public static final String WILDCARD_KEY = "*";

    private final KeyParser keyParser;

    public NormalizedQuery normalize(
            String key,
            MultiValueMap<String, String> filters,
            List<String> nonTimeDimensionIds
    ) {
        LinkedMultiValueMap<String, String> out = new LinkedMultiValueMap<>();
        if (filters != null) {
            filters.forEach((dim, values) -> {
                if (values != null && !values.isEmpty()) {
                    out.put(dim, new ArrayList<>(values));
                }
            });
        }
        Map<String, List<String>> keyState = keyParser.parseKey(key, nonTimeDimensionIds);
        for (Map.Entry<String, List<String>> entry : keyState.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            String dim = entry.getKey();
            List<String> existing = out.get(dim);
            if (existing == null || existing.isEmpty()) {
                out.put(dim, new ArrayList<>(values));
            } else {
                LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
                merged.addAll(values);
                out.put(dim, new ArrayList<>(merged));
            }
        }
        return new NormalizedQuery(WILDCARD_KEY, out);
    }

    public record NormalizedQuery(String key, MultiValueMap<String, String> filters) {
    }
}
