package com.epam.sdmxproxy.common.utils;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;


public final class StreamUtils {
    private StreamUtils() {
    }

    public static <T> Stream<T> streamOfNullable(Collection<T> list) {
        return Stream.ofNullable(list)
                .flatMap(Collection::stream);
    }

    public static <S, T> Stream<T> streamFromNullableExtractingCollection(S source, Function<S, Collection<T>> extractor) {
        return Optional.ofNullable(source)
                .map(extractor)
                .stream()
                .flatMap(Collection::stream);
    }

    public static <K, V> Stream<Map.Entry<K, V>> entryStreamOfNullable(Map<K, V> map) {
        return Optional.ofNullable(map)
                .map(Map::entrySet)
                .stream()
                .flatMap(Set::stream);
    }
}
