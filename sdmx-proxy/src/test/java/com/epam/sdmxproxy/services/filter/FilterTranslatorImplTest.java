package com.epam.sdmxproxy.services.filter;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy's own interface is SDMX 3.0, so narrowing arrives as {@code c[DIM]=A,B} -- comma is the
 * 3.0 OR separator. SDMX 2.1 expresses the same OR as {@code A+B} inside the key position, so the
 * translation is a separator swap, not a reason to reject the request.
 *
 * <p>Dimension order is the DSD-declared order of {@code OECD.SDD.STES:DSD_STES@DF_BTS(4.0)}, whose
 * alphabetical order differs at almost every position -- see {@code Sdmx21KeyOrderTest}.
 */
class FilterTranslatorImplTest {

    private static final List<String> BTS_DECLARED_ORDER = List.of("REF_AREA", "FREQ", "MEASURE", "UNIT_MEASURE", "ACTIVITY", "ADJUSTMENT", "TRANSFORMATION", "TIME_HORIZ", "METHODOLOGY");

    private final FilterTranslatorImpl filterTranslator = new FilterTranslatorImpl();

    @Test
    void shouldJoinCommaSeparatedAlternativesWithPlus() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "ITA");
        filters.add("FREQ", "M");
        filters.add("ACTIVITY", "F,GTU,C");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("ITA.M.*.*.F+GTU+C.*.*.*.*");
    }

    @Test
    void shouldJoinRepeatedParametersWithPlus() {
        // c[ACTIVITY]=F&c[ACTIVITY]=GTU is the other legal SDMX 3.0 spelling of the same OR.
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("ACTIVITY", "F");
        filters.add("ACTIVITY", "GTU");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("*.*.*.*.F+GTU.*.*.*.*");
    }

    @Test
    void shouldStripTheEqOperatorFromEveryAlternative() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("ACTIVITY", "eq:F,eq:GTU");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("*.*.*.*.F+GTU.*.*.*.*");
    }

    @Test
    void shouldDeduplicateRepeatedAlternativesPreservingOrder() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("ACTIVITY", "F,GTU");
        filters.add("ACTIVITY", "F");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("*.*.*.*.F+GTU.*.*.*.*");
    }

    @Test
    void shouldKeepSingleValuesUnchanged() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("ACTIVITY", "C");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("*.*.*.*.C.*.*.*.*");
    }

    @Test
    void shouldIgnoreFiltersOnComponentsOutsideTheKey() {
        // TIME_PERIOD never occupies a key position -- it travels as startPeriod/endPeriod.
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "ITA");
        filters.add("TIME_PERIOD", "ge:2020+le:2021");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("ITA.*.*.*.*.*.*.*.*");
    }
}
