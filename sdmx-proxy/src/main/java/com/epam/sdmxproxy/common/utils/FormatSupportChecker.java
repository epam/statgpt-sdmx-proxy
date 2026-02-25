package com.epam.sdmxproxy.common.utils;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import java.util.List;

/**
 * Utility class for checking if a registry supports a requested output format.
 * Used to determine if bypass logic can be applied (direct return without conversion).
 */
@UtilityClass
public class FormatSupportChecker {

    /**
     * Checks if bypass can be used for data queries.
     * Bypass is possible if:
     * 1. bypassEnabled is true
     * 2. requested format matches at least one format in supportedFormats list
     *
     * @param versionConfig      version-specific registry configuration
     * @param requestedMediaType requested media type (from Accept header)
     * @return true if bypass can be used (direct return without conversion)
     */
    public static boolean canBypassDataFormat(
            VersionSpecificRegistryConfiguration versionConfig,
            MediaType requestedMediaType
    ) {
        DataEndpointConfiguration dataConfig = versionConfig.getDataEndpointConfig();
        if (dataConfig == null) {
            return false;
        }
        return canBypass(dataConfig.isBypassEnabled(), dataConfig.getSupportedFormats(), requestedMediaType);
    }

    /**
     * Checks if bypass can be used for structure queries.
     *
     * @param versionConfig      version-specific registry configuration
     * @param requestedMediaType requested media type (from Accept header)
     * @return true if bypass can be used (direct return without conversion)
     */
    public static boolean canBypassStructureFormat(
            VersionSpecificRegistryConfiguration versionConfig,
            MediaType requestedMediaType
    ) {
        StructureEndpointConfiguration structureConfig = versionConfig.getStructureEndpointConfig();
        if (structureConfig == null) {
            return false;
        }
        return canBypass(structureConfig.isBypassEnabled(), structureConfig.getSupportedFormats(), requestedMediaType);
    }

    /**
     * Checks if bypass can be used for availability queries.
     * For availability, typically uses the same format as structures.
     *
     * @param versionConfig      version-specific registry configuration
     * @param requestedMediaType requested media type (from Accept header)
     * @return true if bypass can be used (direct return without conversion)
     */
    public static boolean canBypassAvailabilityFormat(
            VersionSpecificRegistryConfiguration versionConfig,
            MediaType requestedMediaType
    ) {
        // Availability typically uses structure endpoint configuration
        StructureEndpointConfiguration structureConfig = versionConfig.getStructureEndpointConfig();
        if (structureConfig == null) {
            return false;
        }
        return canBypass(structureConfig.isBypassEnabled(), structureConfig.getSupportedFormats(), requestedMediaType);
    }

    /**
     * Checks if bypass can be used based on bypass flag and supported formats list.
     *
     * @param bypassEnabled      whether bypass is enabled
     * @param supportedFormats   list of formats that registry can return
     * @param requestedMediaType media type requested by client
     * @return true if bypass can be used
     */
    private static boolean canBypass(boolean bypassEnabled, List<ReturnFormat> supportedFormats, MediaType requestedMediaType) {
        if (!bypassEnabled || supportedFormats == null || supportedFormats.isEmpty()) {
            return false;
        }
        // Check if any format in supportedFormats matches the requested media type
        return supportedFormats.stream()
                .anyMatch(format -> formatMatches(format, requestedMediaType));
    }

    /**
     * Compares ReturnFormat (registry format) with MediaType (requested format).
     *
     * @param registryFormat     format that registry returns natively
     * @param requestedMediaType media type requested by client
     * @return true if formats match
     */
    public static boolean formatMatches(ReturnFormat registryFormat, MediaType requestedMediaType) {
        String contentType = registryFormat.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            // CSV format has empty content type, match by type/subtype
            return requestedMediaType.getType().equals("text") && requestedMediaType.getSubtype().equals("csv") ||
                    requestedMediaType.getType().equals("application") && requestedMediaType.getSubtype().contains("csv");
        }
        MediaType registryMediaType = MediaType.valueOf(contentType);
        // Compare type and subtype (ignoring parameters like version)
        return SdmxMediaType.isMatch(registryMediaType, requestedMediaType);
    }
}
