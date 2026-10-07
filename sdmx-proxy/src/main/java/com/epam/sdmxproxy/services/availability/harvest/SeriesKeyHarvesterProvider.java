package com.epam.sdmxproxy.services.availability.harvest;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link SeriesKeyHarvester} for a probe format, from the registered beans.
 * Mirrors {@code SeriesLimitTruncatorProvider}.
 */
@Slf4j
@Component
public class SeriesKeyHarvesterProvider {

    private final Map<SdmxFormat, SeriesKeyHarvester> byFormat;

    public SeriesKeyHarvesterProvider(List<SeriesKeyHarvester> harvesters) {
        Map<SdmxFormat, SeriesKeyHarvester> map = new EnumMap<>(SdmxFormat.class);
        for (SeriesKeyHarvester harvester : harvesters) {
            for (SdmxFormat format : harvester.supportedFormats()) {
                SeriesKeyHarvester prior = map.putIfAbsent(format, harvester);
                if (prior != null) {
                    throw new UnexpectedStateException(
                            "Multiple SeriesKeyHarvester implementations registered for "
                                    + format + ": " + prior.getClass().getName()
                                    + " and " + harvester.getClass().getName());
                }
            }
        }
        this.byFormat = Map.copyOf(map);
    }

    public SeriesKeyHarvester forFormat(SdmxFormat format) {
        SeriesKeyHarvester harvester = byFormat.get(format);
        if (harvester == null) {
            throw new AvailabilityEmulationException(
                    "No series-key harvester for probe format " + format
                            + "; harvestable formats are " + byFormat.keySet());
        }
        return harvester;
    }

    public boolean isSupported(SdmxFormat format) {
        return byFormat.containsKey(format);
    }

    public Collection<SdmxFormat> supportedFormats() {
        return byFormat.keySet();
    }
}
