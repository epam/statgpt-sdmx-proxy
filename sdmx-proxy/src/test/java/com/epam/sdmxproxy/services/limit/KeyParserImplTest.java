package com.epam.sdmxproxy.services.limit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeyParserImplTest {

    private static final List<String> DIMS = List.of("FREQ", "EER_TYPE", "EER_BASKET", "REF_AREA");
    private final KeyParser keyParser = new KeyParserImpl();

    @Test
    void parse_null_returnsAllWildcards() {
        Map<String, List<String>> map = keyParser.parseKey(null, DIMS);
        assertAllWildcard(map);
    }

    @Test
    void parse_empty_returnsAllWildcards() {
        assertAllWildcard(keyParser.parseKey("", DIMS));
    }

    @Test
    void parse_star_returnsAllWildcards() {
        assertAllWildcard(keyParser.parseKey("*", DIMS));
    }

    @Test
    void parse_allKeyword_returnsAllWildcards() {
        assertAllWildcard(keyParser.parseKey("all", DIMS));
    }

    @Test
    void parse_positionalFourDims() {
        Map<String, List<String>> map = keyParser.parseKey("M.N.B.DE", DIMS);
        assertThat(map.get("FREQ")).containsExactly("M");
        assertThat(map.get("EER_TYPE")).containsExactly("N");
        assertThat(map.get("EER_BASKET")).containsExactly("B");
        assertThat(map.get("REF_AREA")).containsExactly("DE");
    }

    @Test
    void parse_withPlusOrWithinPosition() {
        Map<String, List<String>> map = keyParser.parseKey("M+D.*.*.DE+FR", DIMS);
        assertThat(map.get("FREQ")).containsExactly("M", "D");
        assertThat(map.get("EER_TYPE")).isEmpty();
        assertThat(map.get("EER_BASKET")).isEmpty();
        assertThat(map.get("REF_AREA")).containsExactly("DE", "FR");
    }

    @Test
    void parse_emptyPositionIsWildcard() {
        Map<String, List<String>> map = keyParser.parseKey("M..B.DE", DIMS);
        assertThat(map.get("FREQ")).containsExactly("M");
        assertThat(map.get("EER_TYPE")).isEmpty();
        assertThat(map.get("EER_BASKET")).containsExactly("B");
        assertThat(map.get("REF_AREA")).containsExactly("DE");
    }

    @Test
    void parse_shorterKey_trailingPositionsWildcard() {
        Map<String, List<String>> map = keyParser.parseKey("M.N", DIMS);
        assertThat(map.get("FREQ")).containsExactly("M");
        assertThat(map.get("EER_TYPE")).containsExactly("N");
        assertThat(map.get("EER_BASKET")).isEmpty();
        assertThat(map.get("REF_AREA")).isEmpty();
    }

    @Test
    void build_allWildcard_collapsesWhenFlagTrue() {
        assertThat(keyParser.buildKey(allWildcards(), DIMS, true)).isEqualTo("*");
    }

    @Test
    void build_allWildcard_expandsWhenFlagFalse() {
        assertThat(keyParser.buildKey(allWildcards(), DIMS, false)).isEqualTo("*.*.*.*");
    }

    @Test
    void build_mixed_joinsWithDotsAndPlus() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("FREQ", List.of("M", "D"));
        map.put("EER_TYPE", List.of());
        map.put("EER_BASKET", List.of());
        map.put("REF_AREA", List.of("DE", "FR"));
        assertThat(keyParser.buildKey(map, DIMS, true)).isEqualTo("M+D.*.*.DE+FR");
    }

    @Test
    void build_singleValuePerPosition_noStraySeparators() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String dim : DIMS) {
            map.put(dim, List.of("X"));
        }
        assertThat(keyParser.buildKey(map, DIMS, true)).isEqualTo("X.X.X.X");
    }

    @Test
    void roundTrip_parseThenBuildWithCollapse() {
        Map<String, List<String>> map = keyParser.parseKey("M+D.*.*.DE", DIMS);
        assertThat(keyParser.buildKey(map, DIMS, true)).isEqualTo("M+D.*.*.DE");
    }

    @Test
    void roundTrip_starThenBuildCollapses() {
        Map<String, List<String>> map = keyParser.parseKey("*", DIMS);
        assertThat(keyParser.buildKey(map, DIMS, true)).isEqualTo("*");
        assertThat(keyParser.buildKey(map, DIMS, false)).isEqualTo("*.*.*.*");
    }

    private static Map<String, List<String>> allWildcards() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String dim : DIMS) {
            map.put(dim, List.of());
        }
        return map;
    }

    private static void assertAllWildcard(Map<String, List<String>> map) {
        assertThat(map).containsOnlyKeys(DIMS.toArray(new String[0]));
        for (String dim : DIMS) {
            assertThat(map.get(dim)).as("dim %s should be wildcard", dim).isEmpty();
        }
    }
}
