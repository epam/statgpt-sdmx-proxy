package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

/**
 * Version-specific configuration for an SDMX registry.
 * Contains all settings that differ between SDMX versions (2.1, 3.0, etc.)
 */
@Data
public class VersionSpecificRegistryConfiguration {

    private SdmxVersion sdmxVersion;

    private StructureEndpointConfiguration structureEndpointConfig;

    private DataEndpointConfiguration dataEndpointConfig;

    private AvailabilityEndpointConfiguration availabilityEndpointConfig;

    private RegistryResilienceConfig resilienceConfig;

    /**
     * When true, outbound requests to this registry version carry {@code Accept-Encoding: identity}
     * so the registry answers uncompressed.
     * <p>
     * Required for registries that compress the body without setting {@code Content-Encoding}.
     * Eurostat gzips whenever the request asks for {@code gzip} and signals it only through
     * {@code Content-Disposition: attachment; filename="....xml.gz"}; OkHttp decompresses on
     * {@code Content-Encoding} alone, so the SDMX readers receive the raw gzip stream and fail with
     * a null {@code SdmxBeans} or {@code SdmxSyntaxException: 800}.
     * <p>
     * Applies to all three endpoints of this version -- compression is a transport concern, and no
     * registry has been seen to compress one endpoint and not another. Default is false, so existing
     * registries keep compressed transfers.
     */
    private boolean disableCompression;

}
