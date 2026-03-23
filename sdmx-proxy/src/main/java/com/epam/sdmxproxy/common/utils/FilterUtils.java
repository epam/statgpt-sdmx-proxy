package com.epam.sdmxproxy.common.utils;

import com.epam.sdmxproxy.common.data.DataComponentFilterDto;
import com.epam.sdmxproxy.common.data.DataComponentFilterOperator;
import org.jetbrains.annotations.NotNull;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class FilterUtils {

    public static final String PLUS_DELIMITER = "+";
    public static final String KEY_FORMAT = "c[%s]";

    private FilterUtils() {
    }

    /**
     * Converts List of DataComponentFilterDto to MultiValueMap format expected by QueryTranslator.
     * Groups filters by componentCode and combines them using '+' (AND separator).
     * Format: key = "c[componentCode]", value = "operator1:value1+operator2:value2" or just "value" if operator is eq/null
     * <p>
     * Example:
     * Input: [
     * {"componentCode":"TIME_PERIOD","operator":"ge","value":"2020-01"},
     * {"componentCode":"TIME_PERIOD","operator":"lt","value":"2020-12"}
     * ]
     * Output: c[TIME_PERIOD]=ge:2020-01+lt:2020-12
     *
     * @param filters List of filter DTOs to convert
     * @return MultiValueMap with format "c[componentCode]" -> combined values, or empty map if filters is null/empty
     */
    public static MultiValueMap<String, String> convertFiltersToMultiValueMap(List<DataComponentFilterDto> filters) {
        if (filters == null || filters.isEmpty()) {
            return new LinkedMultiValueMap<>();
        }

        Map<String, List<DataComponentFilterDto>> groupedFilters = groupByComponentCode(filters);

        if (groupedFilters.isEmpty()) {
            return new LinkedMultiValueMap<>();
        }

        return groupedFilters.entrySet().stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                formKey(),
                                formValue()
                        ),
                        convertToMultiValueMap()
                ));
    }

    @NotNull
    private static Function<Map<String, String>, MultiValueMap<String, String>> convertToMultiValueMap() {
        return map -> {
            MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
            map.forEach(multiValueMap::add);
            return multiValueMap;
        };
    }

    @NotNull
    private static Function<Map.Entry<String, List<DataComponentFilterDto>>, String> formValue() {
        return entry -> entry.getValue().stream()
                .map(filter -> formatFilterValue(filter.getValue(), filter.getOperator()))
                .collect(Collectors.joining(PLUS_DELIMITER));
    }

    @NotNull
    private static Function<Map.Entry<String, List<DataComponentFilterDto>>, String> formKey() {
        return entry -> String.format(KEY_FORMAT, entry.getKey());
    }

    @NotNull
    private static Map<String, List<DataComponentFilterDto>> groupByComponentCode(List<DataComponentFilterDto> filters) {
        return filters.stream()
                .filter(filter -> filter.getComponentCode() != null && filter.getValue() != null)
                .collect(Collectors.groupingBy(DataComponentFilterDto::getComponentCode));
    }

    /**
     * Formats filter value with operator prefix if needed.
     * Default operator (eq) or null operator doesn't need prefix.
     *
     * @param value    Filter value
     * @param operator Filter operator (can be null, defaults to eq)
     * @return Formatted value: "operator:value" or just "value" if operator is eq/null
     */
    public static String formatFilterValue(String value, DataComponentFilterOperator operator) {
        if (operator == null || operator == DataComponentFilterOperator.eq) {
            return value;
        }
        return operator.name() + ":" + value;
    }
}
