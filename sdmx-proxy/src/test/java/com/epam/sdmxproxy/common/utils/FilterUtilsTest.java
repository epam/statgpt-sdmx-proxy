package com.epam.sdmxproxy.common.utils;

import com.epam.sdmxproxy.common.data.DataComponentFilterDto;
import com.epam.sdmxproxy.common.data.DataComponentFilterOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameterized test suite for FilterUtils.convertFiltersToMultiValueMap method.
 * Tests various scenarios including edge cases.
 */
@DisplayName("FilterUtils Tests")
class FilterUtilsTest {

    // ========== Edge Cases ==========

    static Stream<Arguments> provideTestCases() {
        return Stream.of(
                // Basic single filter
                Arguments.of(
                        "Single filter with eq operator",
                        List.of(createFilter("FREQ", "A", DataComponentFilterOperator.eq)),
                        createExpectedMap("c[FREQ]", "A")
                ),

                // Single filter with null operator (should default to eq)
                Arguments.of(
                        "Single filter with null operator",
                        List.of(createFilter("FREQ", "A", null)),
                        createExpectedMap("c[FREQ]", "A")
                ),

                // Single filter with non-eq operator
                Arguments.of(
                        "Single filter with ne operator",
                        List.of(createFilter("FREQ", "A", DataComponentFilterOperator.ne)),
                        createExpectedMap("c[FREQ]", "ne:A")
                ),

                // Multiple filters with same componentCode (should combine with +)
                Arguments.of(
                        "Multiple filters with same componentCode - time range",
                        List.of(
                                createFilter("TIME_PERIOD", "2020-01", DataComponentFilterOperator.ge),
                                createFilter("TIME_PERIOD", "2020-12", DataComponentFilterOperator.lt)
                        ),
                        createExpectedMap("c[TIME_PERIOD]", "ge:2020-01+lt:2020-12")
                ),

                // Multiple filters with same componentCode, one with eq operator
                Arguments.of(
                        "Multiple filters with same componentCode - one eq, one ne",
                        List.of(
                                createFilter("FREQ", "A", DataComponentFilterOperator.eq),
                                createFilter("FREQ", "M", DataComponentFilterOperator.ne)
                        ),
                        createExpectedMap("c[FREQ]", "A+ne:M")
                ),

                // Multiple filters with different componentCodes
                Arguments.of(
                        "Multiple filters with different componentCodes",
                        List.of(
                                createFilter("FREQ", "A", DataComponentFilterOperator.eq),
                                createFilter("REF_AREA", "US", DataComponentFilterOperator.eq)
                        ),
                        createExpectedMap(
                                "c[FREQ]", "A",
                                "c[REF_AREA]", "US"
                        )
                ),

                // Complex scenario: multiple filters, some same componentCode, different operators
                Arguments.of(
                        "Complex scenario - multiple componentCodes with multiple filters",
                        List.of(
                                createFilter("TIME_PERIOD", "2020-01", DataComponentFilterOperator.ge),
                                createFilter("TIME_PERIOD", "2020-12", DataComponentFilterOperator.lt),
                                createFilter("FREQ", "A", DataComponentFilterOperator.eq),
                                createFilter("FREQ", "Q", DataComponentFilterOperator.eq),
                                createFilter("REF_AREA", "US", DataComponentFilterOperator.ne)
                        ),
                        createExpectedMap(
                                "c[TIME_PERIOD]", "ge:2020-01+lt:2020-12",
                                "c[FREQ]", "A+Q",
                                "c[REF_AREA]", "ne:US"
                        )
                ),

                // All operators test
                Arguments.of(
                        "All different operators",
                        List.of(
                                createFilter("TEST", "value1", DataComponentFilterOperator.eq),
                                createFilter("TEST", "value2", DataComponentFilterOperator.ne),
                                createFilter("TEST", "value3", DataComponentFilterOperator.lt),
                                createFilter("TEST", "value4", DataComponentFilterOperator.le),
                                createFilter("TEST", "value5", DataComponentFilterOperator.gt),
                                createFilter("TEST", "value6", DataComponentFilterOperator.ge),
                                createFilter("TEST", "value7", DataComponentFilterOperator.co),
                                createFilter("TEST", "value8", DataComponentFilterOperator.nc),
                                createFilter("TEST", "value9", DataComponentFilterOperator.sw),
                                createFilter("TEST", "value10", DataComponentFilterOperator.ew)
                        ),
                        createExpectedMap("c[TEST]",
                                "value1+ne:value2+lt:value3+le:value4+gt:value5+ge:value6+co:value7+nc:value8+sw:value9+ew:value10")
                ),

                // Empty string values
                Arguments.of(
                        "Filters with empty string values",
                        List.of(
                                createFilter("FREQ", "", DataComponentFilterOperator.eq),
                                createFilter("REF_AREA", "", DataComponentFilterOperator.ne)
                        ),
                        createExpectedMap(
                                "c[FREQ]", "",
                                "c[REF_AREA]", "ne:"
                        )
                ),

                // Special characters in values
                Arguments.of(
                        "Filters with special characters in values",
                        List.of(
                                createFilter("TEST", "value+test", DataComponentFilterOperator.eq),
                                createFilter("TEST", "value:test", DataComponentFilterOperator.ne)
                        ),
                        createExpectedMap("c[TEST]", "value+test+ne:value:test")
                ),

                // Multiple filters with null operators (all should default to eq)
                Arguments.of(
                        "Multiple filters with null operators",
                        List.of(
                                createFilter("FREQ", "A", null),
                                createFilter("FREQ", "M", null),
                                createFilter("REF_AREA", "US", null)
                        ),
                        createExpectedMap(
                                "c[FREQ]", "A+M",
                                "c[REF_AREA]", "US"
                        )
                ),

                // Single filter with each operator type
                Arguments.of(
                        "Single filter with ge operator",
                        List.of(createFilter("TIME_PERIOD", "2020-01", DataComponentFilterOperator.ge)),
                        createExpectedMap("c[TIME_PERIOD]", "ge:2020-01")
                ),

                Arguments.of(
                        "Single filter with le operator",
                        List.of(createFilter("TIME_PERIOD", "2020-12", DataComponentFilterOperator.le)),
                        createExpectedMap("c[TIME_PERIOD]", "le:2020-12")
                ),

                Arguments.of(
                        "Single filter with gt operator",
                        List.of(createFilter("VALUE", "100", DataComponentFilterOperator.gt)),
                        createExpectedMap("c[VALUE]", "gt:100")
                ),

                Arguments.of(
                        "Single filter with co operator",
                        List.of(createFilter("TEXT", "search", DataComponentFilterOperator.co)),
                        createExpectedMap("c[TEXT]", "co:search")
                ),

                Arguments.of(
                        "Single filter with sw operator",
                        List.of(createFilter("TEXT", "prefix", DataComponentFilterOperator.sw)),
                        createExpectedMap("c[TEXT]", "sw:prefix")
                ),

                Arguments.of(
                        "Single filter with ew operator",
                        List.of(createFilter("TEXT", "suffix", DataComponentFilterOperator.ew)),
                        createExpectedMap("c[TEXT]", "ew:suffix")
                )
        );
    }

    private static DataComponentFilterDto createFilter(
            String componentCode,
            String value,
            DataComponentFilterOperator operator
    ) {
        return DataComponentFilterDto.builder()
                .componentCode(componentCode)
                .value(value)
                .operator(operator)
                .build();
    }

    private static MultiValueMap<String, String> createExpectedMap(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Must provide even number of arguments (key-value pairs)");
        }

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.add(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("Should return empty map when filters is null")
    void testConvertFiltersToMultiValueMap_NullInput() {
        // GIVEN
        List<DataComponentFilterDto> filters = null;

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(filters);

        // THEN
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return empty map when filters is empty")
    void testConvertFiltersToMultiValueMap_EmptyList() {
        // GIVEN
        List<DataComponentFilterDto> filters = Collections.emptyList();

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(filters);

        // THEN
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ========== Parameterized Tests ==========

    @Test
    @DisplayName("Should return empty map when all filters have null componentCode")
    void testConvertFiltersToMultiValueMap_AllNullComponentCode() {
        // GIVEN
        List<DataComponentFilterDto> filters = List.of(
                DataComponentFilterDto.builder()
                        .componentCode(null)
                        .value("A")
                        .operator(DataComponentFilterOperator.eq)
                        .build()
        );

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(filters);

        // THEN
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return empty map when all filters have null value")
    void testConvertFiltersToMultiValueMap_AllNullValue() {
        // GIVEN
        List<DataComponentFilterDto> filters = List.of(
                DataComponentFilterDto.builder()
                        .componentCode("FREQ")
                        .value(null)
                        .operator(DataComponentFilterOperator.eq)
                        .build()
        );

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(filters);

        // THEN
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ========== Helper Methods ==========

    @Test
    @DisplayName("Should filter out invalid filters and process valid ones")
    void testConvertFiltersToMultiValueMap_MixedValidInvalid() {
        // GIVEN
        List<DataComponentFilterDto> filters = List.of(
                DataComponentFilterDto.builder()
                        .componentCode(null)
                        .value("A")
                        .build(), // Invalid - null componentCode
                DataComponentFilterDto.builder()
                        .componentCode("FREQ")
                        .value(null)
                        .build(), // Invalid - null value
                DataComponentFilterDto.builder()
                        .componentCode("FREQ")
                        .value("A")
                        .operator(DataComponentFilterOperator.eq)
                        .build() // Valid
        );

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(filters);

        // THEN
        assertEquals(1, result.size());
        assertEquals("A", result.getFirst("c[FREQ]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestCases")
    @DisplayName("Should convert filters correctly")
    void testConvertFiltersToMultiValueMap(
            String testName,
            List<DataComponentFilterDto> input,
            MultiValueMap<String, String> expected
    ) {
        // GIVEN
        // Input and expected are provided as parameters

        // WHEN
        MultiValueMap<String, String> result = FilterUtils.convertFiltersToMultiValueMap(input);

        // THEN
        assertNotNull(result, "Result should not be null");

        if (expected == null || expected.isEmpty()) {
            assertTrue(result.isEmpty(), "Expected empty result");
        } else {
            assertEquals(expected.size(), result.size(), "Result size should match");
            expected.forEach((key, values) -> {
                List<String> resultValues = result.get(key);
                assertEquals(values, resultValues,
                        String.format("Values for key '%s' should match", key));
            });
        }
    }
}
