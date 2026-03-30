package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.epam.sdmxproxy.registry.configuration.extractor.ProxyConfigurationExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyConfigurationProviderImpl implements ProxyConfigurationProvider {

    private final Set<ProxyConfigurationExtractor> configExtractors;
    @Value("${sdmxproxy.registry.config.source.type}")
    private ProxyConfigurationSourceType sourceType;

    /**
     * Runtime override for test/debug purposes (e.g., E2E tests pushing config via POST /config).
     * When set, takes precedence over the extractor.
     */
    private final AtomicReference<ProxyConfiguration> runtimeOverride = new AtomicReference<>();

    public ProxyConfiguration getConfiguration() {
        ProxyConfiguration override = runtimeOverride.get();
        if (override != null) {
            return override;
        }

        ProxyConfigurationExtractor configurationExtractor = configExtractors.stream()
                .filter(extractor -> extractor.supports() == sourceType)
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(String.format("%s is not supported", sourceType)));

        return configurationExtractor.getConfiguration();
    }

    /**
     * Sets a runtime configuration override. Used by test endpoints.
     * Pass null to clear the override and revert to the configured extractor.
     */
    public void setRuntimeOverride(ProxyConfiguration configuration) {
        runtimeOverride.set(configuration);
    }
}
