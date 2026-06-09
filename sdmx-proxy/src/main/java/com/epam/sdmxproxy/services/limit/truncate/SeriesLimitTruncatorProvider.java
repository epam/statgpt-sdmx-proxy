package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link SeriesLimitTruncator} matching a given {@link SdmxFormat}.
 * Constructed from the list of Spring-registered {@link SeriesLimitTruncator} beans.
 */
@Slf4j
@Component
public class SeriesLimitTruncatorProvider {

    private final Map<SdmxFormat, SeriesLimitTruncator> byFormat;

    public SeriesLimitTruncatorProvider(List<SeriesLimitTruncator> truncators) {
        Map<SdmxFormat, SeriesLimitTruncator> map = new EnumMap<>(SdmxFormat.class);
        for (SeriesLimitTruncator t : truncators) {
            for (SdmxFormat fmt : t.supportedFormats()) {
                SeriesLimitTruncator prior = map.putIfAbsent(fmt, t);
                if (prior != null) {
                    throw new UnexpectedStateException(
                            "Multiple SeriesLimitTruncator implementations registered for "
                                    + fmt + ": " + prior.getClass().getName()
                                    + " and " + t.getClass().getName());
                }
            }
        }
        this.byFormat = Map.copyOf(map);
    }

    public SeriesLimitTruncator forFormat(SdmxFormat format) {
        SeriesLimitTruncator t = byFormat.get(format);
        if (t == null) {
            throw new UnexpectedStateException(
                    "No SeriesLimitTruncator registered for return format " + format);
        }
        return t;
    }

    public boolean isSupported(SdmxFormat format) {
        return byFormat.containsKey(format);
    }

    public Collection<SdmxFormat> supportedFormats() {
        return byFormat.keySet();
    }
}
