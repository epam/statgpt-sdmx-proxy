package com.epam.sdmxproxy.services.availability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.epam.sdmxproxy.exception.AvailabilityNotSupportedException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link AvailabilityEmulator} for a configured emulation type, from the registered
 * beans. Mirrors {@code SeriesLimitTruncatorProvider}.
 */
@Component
public class AvailabilityEmulatorProvider {

    private final Map<AvailabilityEmulationType, AvailabilityEmulator> byType;

    public AvailabilityEmulatorProvider(List<AvailabilityEmulator> emulators) {
        Map<AvailabilityEmulationType, AvailabilityEmulator> map = new EnumMap<>(AvailabilityEmulationType.class);
        for (AvailabilityEmulator emulator : emulators) {
            AvailabilityEmulator prior = map.putIfAbsent(emulator.supportedType(), emulator);
            if (prior != null) {
                throw new UnexpectedStateException(
                        "Multiple AvailabilityEmulator implementations registered for "
                                + emulator.supportedType() + ": " + prior.getClass().getName()
                                + " and " + emulator.getClass().getName());
            }
        }
        this.byType = Map.copyOf(map);
    }

    /**
     * @throws AvailabilityNotSupportedException (HTTP 501) when nothing serves {@code type}
     */
    public AvailabilityEmulator forType(AvailabilityEmulationType type) {
        AvailabilityEmulator emulator = byType.get(type);
        if (emulator == null) {
            throw new AvailabilityNotSupportedException(
                    "No availability emulator for type " + type);
        }
        return emulator;
    }
}
