package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies data-endpoint fixtures as a streaming chain of responsibility.
 * <p>
 * The service itself holds no bytes: each call to {@link DataFixture#apply} returns a wrapped
 * {@link InputStream}, and this service passes that wrapped stream into the next fixture in
 * the chain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFixtureService {

    private final List<DataFixture> fixtures;

    public InputStream applyFixtures(
            InputStream input,
            SdmxFormat format,
            SdmxBeans sdmxBeans,
            List<FixtureConfiguration<DataFixtureType>> fixtureConfigs
    ) {
        return applyFixtures(input, format, sdmxBeans, fixtureConfigs, null);
    }

    /**
     * Applies only the configured fixtures whose type is in {@code onlyTypes}; null applies them
     * all. The availability emulation probe uses it to run the transport repair alone (design 043).
     * <p>
     * {@link DataFixtureType#REPAIR_EMPTY_DATASET} always runs first, whatever its position in the
     * list: it turns malformed XML into well-formed XML, and a StAX-based fixture ahead of it would
     * fail on the malformed body.
     */
    public InputStream applyFixtures(
            InputStream input,
            SdmxFormat format,
            SdmxBeans sdmxBeans,
            List<FixtureConfiguration<DataFixtureType>> fixtureConfigs,
            Set<DataFixtureType> onlyTypes
    ) {
        if (fixtureConfigs == null || fixtureConfigs.isEmpty()) {
            return input;
        }

        List<FixtureConfiguration<DataFixtureType>> ordered = new ArrayList<>();
        fixtureConfigs.stream().filter(fc -> fc.getType() == DataFixtureType.REPAIR_EMPTY_DATASET).forEach(ordered::add);
        fixtureConfigs.stream().filter(fc -> fc.getType() != DataFixtureType.REPAIR_EMPTY_DATASET).forEach(ordered::add);

        InputStream current = input;
        for (FixtureConfiguration<DataFixtureType> fc : ordered) {
            if (onlyTypes != null && !onlyTypes.contains(fc.getType())) {
                continue;
            }
            DataFixture fixture = findFixture(fc.getType(), format);
            if (fixture != null) {
                log.debug("Applying data fixture {} for format {}", fc.getType(), format);
                current = fixture.apply(current, sdmxBeans, fc.getConfig());
            } else {
                log.debug("No data fixture implementation for type {} and format {}, skipping", fc.getType(), format);
            }
        }
        return current;
    }

    private DataFixture findFixture(DataFixtureType type, SdmxFormat format) {
        return fixtures.stream()
                .filter(f -> f.getType() == type && f.supportedFormats().contains(format))
                .findFirst()
                .orElse(null);
    }
}
