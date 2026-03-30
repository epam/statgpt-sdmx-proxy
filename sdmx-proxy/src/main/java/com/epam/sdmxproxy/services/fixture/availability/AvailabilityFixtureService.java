package com.epam.sdmxproxy.services.fixture.availability;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * Orchestrates applying fixtures to raw registry responses.
 * Fixtures are applied as a chain of responsibility in the order they are configured.
 * For each configured fixture, the service finds a matching implementation by both
 * {@link AvailabilityFixtureType} and supported {@link ReturnFormat}, then applies it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityFixtureService {

    private final List<AvailabilityFixture> fixtures;

    /**
     * Applies configured fixtures to the input stream.
     *
     * @param input          raw response stream from the registry
     * @param format         the return format of the response (determines which fixture impl is used)
     * @param fixtureConfigs list of fixture configurations from the endpoint config
     * @return the input stream with all applicable fixtures applied
     */
    public InputStream applyFixtures(
            InputStream input,
            ReturnFormat format,
            SdmxBeans sdmxBeans,
            List<FixtureConfiguration<AvailabilityFixtureType>> fixtureConfigs
    ) {
        if (fixtureConfigs == null || fixtureConfigs.isEmpty()) {
            return input;
        }

        InputStream current = input;
        for (FixtureConfiguration<AvailabilityFixtureType> fc : fixtureConfigs) {
            AvailabilityFixture fixture = findFixture(fc.getType(), format);
            if (fixture != null) {
                log.debug("Applying fixture {} for format {}", fc.getType(), format);
                current = fixture.apply(current, sdmxBeans, fc.getConfig());
            } else {
                log.debug("No fixture implementation found for type {} and format {}, skipping", fc.getType(), format);
            }
        }
        return current;
    }

    private AvailabilityFixture findFixture(AvailabilityFixtureType type, ReturnFormat format) {
        return fixtures.stream()
                .filter(f -> f.getType() == type && f.supportedFormats().contains(format))
                .findFirst()
                .orElse(null);
    }
}
