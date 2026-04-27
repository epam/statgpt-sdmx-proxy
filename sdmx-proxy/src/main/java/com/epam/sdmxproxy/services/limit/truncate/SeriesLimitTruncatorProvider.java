package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link SeriesLimitTruncator} matching a given {@link ReturnFormat}.
 * Constructed from the list of Spring-registered {@link SeriesLimitTruncator} beans.
 */
@Slf4j
@Component
public class SeriesLimitTruncatorProvider {

    private final Map<ReturnFormat, SeriesLimitTruncator> byFormat;

    public SeriesLimitTruncatorProvider(List<SeriesLimitTruncator> truncators) {
        Map<ReturnFormat, SeriesLimitTruncator> map = new EnumMap<>(ReturnFormat.class);
        for (SeriesLimitTruncator t : truncators) {
            for (ReturnFormat fmt : t.supportedFormats()) {
                SeriesLimitTruncator prior = map.putIfAbsent(fmt, t);
                if (prior != null) {
                    throw new IllegalStateException(
                            "Multiple SeriesLimitTruncator implementations registered for "
                                    + fmt + ": " + prior.getClass().getName()
                                    + " and " + t.getClass().getName());
                }
            }
        }
        this.byFormat = Map.copyOf(map);
    }

    public SeriesLimitTruncator forFormat(ReturnFormat format) {
        SeriesLimitTruncator t = byFormat.get(format);
        if (t == null) {
            throw new IllegalStateException(
                    "No SeriesLimitTruncator registered for return format " + format);
        }
        return t;
    }

    public boolean isSupported(ReturnFormat format) {
        return byFormat.containsKey(format);
    }

    public Collection<ReturnFormat> supportedFormats() {
        return byFormat.keySet();
    }
}
