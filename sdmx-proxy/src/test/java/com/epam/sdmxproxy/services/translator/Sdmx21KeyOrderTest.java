package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.services.filter.FilterTranslatorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An SDMX 2.1 key is read by position, so the dimension order used to place filter values must be
 * the order the DSD declares -- never the alphabetical order of the dimension IDs.
 *
 * <p>Regression pin for the OECD BTS failure: {@code DimensionService} used to hand back an
 * unordered {@code Set} and {@code QueryTranslatorImpl} restored determinism with {@code sorted()},
 * which produced a key of the correct arity with every value under the wrong dimension. The
 * registry answered HTTP 200 with an empty constraint, so nothing downstream could tell that the
 * question asked had been mangled.
 *
 * <p>Fixture is the real {@code OECD.SDD.STES:DSD_STES@DF_BTS(4.0)} dimension list, whose declared
 * order differs from alphabetical at almost every position.
 */
class Sdmx21KeyOrderTest {

    private static final List<String> BTS_DECLARED_ORDER = List.of("REF_AREA", "FREQ", "MEASURE", "UNIT_MEASURE", "ACTIVITY", "ADJUSTMENT", "TRANSFORMATION", "TIME_HORIZ", "METHODOLOGY");

    private final FilterTranslatorImpl filterTranslator = new FilterTranslatorImpl();

    private final Sdmx21QueryNormalizerImpl normalizer = new Sdmx21QueryNormalizerImpl();

    @Test
    void shouldPlaceFilterValuesAtTheirDeclaredPositions() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "ITA");
        filters.add("FREQ", "M");

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        assertThat(key).isEqualTo("ITA.M.*.*.*.*.*.*.*");
    }

    @Test
    void shouldSerializeToTheKeyTheRegistryAnswers() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "ITA");
        filters.add("FREQ", "M");

        String outbound = normalizer.toKey(filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER));

        // Exactly what leaves the proxy. Replayed against sdmx.oecd.org: obs_count=13125, all nine
        // dimensions present in the constraint. The alphabetical sibling `..M...ITA...` returns
        // obs_count=0 with no dimension keyValues at all.
        assertThat(outbound).isEqualTo("ITA.M.......");
    }

    @Test
    void shouldNotProduceTheAlphabeticallyOrderedKey() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("REF_AREA", "ITA");
        filters.add("FREQ", "M");

        String declaredOrderKey = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);
        String alphabeticalKey = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER.stream().sorted().toList());

        // The alphabetical key is well-formed and has the right arity -- it just asks a different,
        // nonsensical question (MEASURE=M, ADJUSTMENT=ITA). The registry answers it with 200 and an
        // empty constraint, which is why this has to be caught here rather than by a status check.
        assertThat(alphabeticalKey).isEqualTo("*.*.M.*.*.ITA.*.*.*");
        assertThat(declaredOrderKey).isNotEqualTo(alphabeticalKey);
    }

    @Test
    void shouldKeepEveryValueAlignedWhenAllDimensionsAreFiltered() {
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        for (String dimension : BTS_DECLARED_ORDER) {
            filters.add(dimension, "V_" + dimension);
        }

        String key = filterTranslator.mergeFiltersIntoKey(null, filters, BTS_DECLARED_ORDER);

        List<String> positions = List.of(key.split("\\.", -1));
        assertThat(positions).hasSameSizeAs(BTS_DECLARED_ORDER);
        for (int i = 0; i < BTS_DECLARED_ORDER.size(); i++) {
            assertThat(positions.get(i))
                    .as("position %d must carry the value of %s", i, BTS_DECLARED_ORDER.get(i))
                    .isEqualTo("V_" + BTS_DECLARED_ORDER.get(i));
        }
    }
}
