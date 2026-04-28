package com.epam.sdmxproxy.services.filter;

import com.epam.sdmxproxy.services.limit.KeyParserImpl;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilterNormalizerTest {

    private final FilterNormalizer normalizer = new FilterNormalizer(new KeyParserImpl());
    private final List<String> dims = List.of("FREQ", "EER_TYPE", "EER_BASKET", "REF_AREA");

    @Test
    void normalize_collapsesWildcardKey_keepsExistingFilters() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ge:2021-04-01+le:2026-04-30");

        FilterNormalizer.NormalizedQuery result = normalizer.normalize("*", filters, dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters()).containsOnlyKeys("TIME_PERIOD");
        assertThat(result.filters().get("TIME_PERIOD")).containsExactly("ge:2021-04-01+le:2026-04-30");
    }

    @Test
    void normalize_movesPositionalKeyValuesIntoCBracket() {
        // M.*.*.AE -> FREQ=[M], REF_AREA=[AE]
        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("M.*.*.AE", new LinkedMultiValueMap<>(), dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters().get("FREQ")).containsExactly("M");
        assertThat(result.filters().get("REF_AREA")).containsExactly("AE");
        assertThat(result.filters()).doesNotContainKeys("EER_TYPE", "EER_BASKET");
    }

    @Test
    void normalize_handlesPositionalPlusOrInKey() {
        // M.*.*.AE+US -> FREQ=[M], REF_AREA=[AE, US]
        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("M.*.*.AE+US", new LinkedMultiValueMap<>(), dims);

        assertThat(result.filters().get("REF_AREA")).containsExactly("AE", "US");
    }

    @Test
    void normalize_unionsKeyAndExistingCBracketForSameDim() {
        // M.*.*.AE plus c[REF_AREA]=US -> REF_AREA=[US, AE]; existing first, key second, deduped
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "US");

        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("M.*.*.AE", filters, dims);

        assertThat(result.filters().get("REF_AREA")).containsExactly("US", "AE");
        assertThat(result.filters().get("FREQ")).containsExactly("M");
    }

    @Test
    void normalize_dedupesValuesThatAppearInBoth() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "AE");
        filters.add("REF_AREA", "US");

        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("M.*.*.AE+JP", filters, dims);

        assertThat(result.filters().get("REF_AREA")).containsExactly("AE", "US", "JP");
    }

    @Test
    void normalize_passesThroughOperatorFiltersUnchanged() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ge:2021-04-01+le:2026-04-30");
        filters.add("OBS_VALUE", "gt:0");

        FilterNormalizer.NormalizedQuery result = normalizer.normalize("M.*.*.*", filters, dims);

        assertThat(result.filters().get("TIME_PERIOD")).containsExactly("ge:2021-04-01+le:2026-04-30");
        assertThat(result.filters().get("OBS_VALUE")).containsExactly("gt:0");
        assertThat(result.filters().get("FREQ")).containsExactly("M");
    }

    @Test
    void normalize_handlesNullKey() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "M");

        FilterNormalizer.NormalizedQuery result = normalizer.normalize(null, filters, dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters().get("FREQ")).containsExactly("M");
    }

    @Test
    void normalize_handlesEmptyFiltersAndWildcardKey() {
        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("*", new LinkedMultiValueMap<>(), dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters()).isEmpty();
    }

    @Test
    void normalize_handlesAllKeyword() {
        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("all", new LinkedMultiValueMap<>(), dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters()).isEmpty();
    }

    @Test
    void normalize_acceptsNullFilters() {
        FilterNormalizer.NormalizedQuery result = normalizer.normalize("M.*.*.*", null, dims);

        assertThat(result.key()).isEqualTo("*");
        assertThat(result.filters().get("FREQ")).containsExactly("M");
    }

    @Test
    void normalize_keyShorterThanDimList_leavesTrailingDimsUnconstrained() {
        // Only 2 positions provided -> FREQ and EER_TYPE; rest stay wildcard
        FilterNormalizer.NormalizedQuery result =
                normalizer.normalize("M.N", new LinkedMultiValueMap<>(), dims);

        assertThat(result.filters().get("FREQ")).containsExactly("M");
        assertThat(result.filters().get("EER_TYPE")).containsExactly("N");
        assertThat(result.filters()).doesNotContainKeys("EER_BASKET", "REF_AREA");
    }
}
