package com.epam.sdmxproxy.registry.api.http;

import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import feign.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class IdentityEncodingClientProviderTest {

    private final IdentityEncodingClientProvider sut = new IdentityEncodingClientProvider();
    private final Client delegate = mock(Client.class);

    @Test
    void shouldReturnDelegateWhenCompressionEnabled() {
        assertSame(delegate, sut.wrap(delegate, selection(false)));
    }

    @Test
    void shouldWrapWhenCompressionDisabled() {
        assertInstanceOf(IdentityEncodingClient.class, sut.wrap(delegate, selection(true)));
    }

    private RegistrySelectionResult selection(boolean disableCompression) {
        RegistryConfiguration registry = new RegistryConfiguration();
        registry.setName("ESTAT");
        VersionSpecificRegistryConfiguration version = new VersionSpecificRegistryConfiguration();
        version.setSdmxVersion(SdmxVersion.SDMX_3_0);
        version.setDisableCompression(disableCompression);
        return RegistrySelectionResult.builder().registryConfiguration(registry).versionConfiguration(version).build();
    }
}
