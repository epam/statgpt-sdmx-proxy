package com.epam.sdmxproxy.registry.configuration;

import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfigurationSourceType;
import com.epam.sdmxproxy.registry.configuration.extractor.ProxyConfigurationExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyConfigurationProviderImpl implements ProxyConfigurationProvider {

    private final Set<ProxyConfigurationExtractor> configExtractors;
    @Value("${sdmxproxy.registry.config.source.type}")
    private ProxyConfigurationSourceType sourceType;

    public ProxyConfiguration getConfiguration() {
        ProxyConfigurationExtractor configurationExtractor = configExtractors.stream()
                .filter(extractor -> extractor.supports() == sourceType)
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(String.format("%s is not supported", sourceType)));

        return configurationExtractor.getConfiguration();
    }


}
