package com.epam.sdmxproxy.registry.api.http;

import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import feign.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Wraps a Feign {@link Client} with {@link IdentityEncodingClient} for registries that opt out of
 * compressed transfers.
 * <p>
 * Returns the delegate unchanged when the registry has not opted out, so registries that handle
 * gzip correctly keep their existing behaviour exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityEncodingClientProvider {

    public Client wrap(Client delegate, RegistrySelectionResult selectedRegistry) {
        if (!selectedRegistry.getVersionConfiguration().isDisableCompression()) {
            return delegate;
        }
        log.debug("Compression disabled for registry {} ({}): sending Accept-Encoding: identity", selectedRegistry.getRegistryConfiguration().getName(), selectedRegistry.getVersionConfiguration().getSdmxVersion());
        return new IdentityEncodingClient(delegate);
    }
}
