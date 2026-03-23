package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.exception.UnsupportedSdmxVersionException;
import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@UtilityClass
public class SdmxMediaType {

    public static final String ANY = "*/*";
    public static final String DRAFT_JSON_2_1_VALUE = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";
    public static final String TEXT_JSON_VALUE = "text/json";
    public static final String SDMX_JSON_1_0_0_VALUE = "application/vnd.sdmx.data+json; version=1.0.0";
    public static final String SDMX_JSON_2_0_0_VALUE = "application/vnd.sdmx.data+json; version=2.0.0";
    public static final String SDMX_XML_3_0_0_VALUE = "application/vnd.sdmx.data+xml; version=3.0.0";
    public static final String APPLICATION_CSV_VALUE = "application/csv";
    public static final String TEXT_CSV_VALUE = "text/csv";
    public static final String SDMX_CSV_1_0_0_VALUE = "application/vnd.sdmx.data+csv; version=1.0.0";
    public static final String SDMX_CSV_2_0_0_VALUE = "application/vnd.sdmx.data+csv; version=2.0.0";
    public static final String SDMX_CSV_VALUE = "application/vnd.sdmx.data+csv";

    public static final String STRUCTURE_SDMX_XML_2_1_VALUE = "application/vnd.sdmx.structure+xml; version=2.1";
    public static final String STRUCTURE_SDMX_JSON_2_0_0_VALUE = "application/vnd.sdmx.structure+json; version=2.0.0";


    static final Set<MediaType> JSON_MEDIA_TYPES = Set.of(
            MediaType.valueOf(DRAFT_JSON_2_1_VALUE),
            MediaType.valueOf(TEXT_JSON_VALUE),
            MediaType.valueOf(SDMX_JSON_1_0_0_VALUE),
            MediaType.valueOf(SDMX_JSON_2_0_0_VALUE),
            MediaType.valueOf(STRUCTURE_SDMX_JSON_2_0_0_VALUE),
            MediaType.APPLICATION_JSON
    );
    static final Set<MediaType> XML_MEDIA_TYPES = Set.of(
            MediaType.valueOf(SDMX_XML_3_0_0_VALUE),
            MediaType.valueOf(STRUCTURE_SDMX_XML_2_1_VALUE),
            MediaType.APPLICATION_XML
    );
    static final Set<MediaType> CSV_MEDIA_TYPES = Set.of(
            MediaType.valueOf(APPLICATION_CSV_VALUE),
            MediaType.valueOf(TEXT_CSV_VALUE),
            MediaType.valueOf(SDMX_CSV_VALUE),
            MediaType.valueOf(SDMX_CSV_1_0_0_VALUE),
            MediaType.valueOf(SDMX_CSV_2_0_0_VALUE)
    );


    public static boolean isMatch(MediaType m1, MediaType m2) {
        return m1.getType().equals(m2.getType())
                && m1.getSubtype().equals(m2.getSubtype());
    }

    public static MediaType mapMediaType(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return MediaType.APPLICATION_JSON;
        }

        List<MediaType> acceptTypes = MediaType.parseMediaTypes(acceptHeader);

        for (MediaType accept : acceptTypes) {
            if (JSON_MEDIA_TYPES.stream().anyMatch(jsonMediaType -> isMatch(jsonMediaType, accept))) {
                return accept;
            }

            if (XML_MEDIA_TYPES.stream().anyMatch(xmlMediaTypes -> isMatch(xmlMediaTypes, accept))) {
                return accept;
            }

            if (CSV_MEDIA_TYPES.stream().anyMatch(csvMediaTypes -> isMatch(csvMediaTypes, accept))) {
                if (accept.equals(MediaType.valueOf(SDMX_CSV_VALUE))) {
                    return MediaType.valueOf(SDMX_CSV_2_0_0_VALUE);
                }
                return accept;
            }
        }

        return MediaType.APPLICATION_JSON;
    }

    /**
     * Parses media type from Accept header and extracts both SDMX version and media type.
     * This is a convenience method that combines mapMediaType and extractSdmxVersion.
     *
     * @param acceptHeader Accept header string (can be null)
     * @return MediaTypeParseResult containing both SDMX version and media type
     * @throws UnsupportedSdmxVersionException if SDMX-specific media type with unsupported version is found
     */
    public static MediaTypeParseResult parseMediaType(String acceptHeader) {
        MediaType mediaType = mapMediaType(acceptHeader);
        SdmxVersion sdmxVersion = extractSdmxVersion(acceptHeader);

        return MediaTypeParseResult.builder()
                .mediaType(mediaType)
                .sdmxVersion(sdmxVersion)
                .build();
    }

    /**
     * Extracts SDMX version from Accept header.
     * Maps specific SDMX media types to their corresponding SDMX versions.
     * Generic media types (application/json, application/xml) and null/blank headers default to SDMX 3.0.
     * Throws exception if SDMX-specific media type with unsupported version is found.
     *
     * @param acceptHeader Accept header string (can be null)
     * @return SdmxVersion extracted from header, SDMX_3_0 for generic types and null/blank headers
     * @throws UnsupportedSdmxVersionException if SDMX-specific media type with unsupported version is found
     */
    public static SdmxVersion extractSdmxVersion(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return SdmxVersion.SDMX_3_0;
        }

        List<MediaType> acceptTypes = MediaType.parseMediaTypes(acceptHeader);
        boolean hasGenericMediaType = false;
        List<String> unsupportedSdmxTypes = new ArrayList<>();

        for (MediaType accept : acceptTypes) {
            String versionParam = accept.getParameter("version");
            String subtype = accept.getSubtype();

            // Check for generic media types (will default to 3.0 if no SDMX-specific type found)
            if (subtype != null && !subtype.contains("sdmx")) {
                if (MediaType.APPLICATION_JSON.equals(accept) || MediaType.APPLICATION_XML.equals(accept)) {
                    hasGenericMediaType = true;
                }
                continue;
            }

            // Process SDMX-specific media types
            if (subtype != null && subtype.contains("sdmx")) {
                // SDMX 3.0 specific media types
                // Check for explicit version 3.0.0
                if ("3.0.0".equals(versionParam)) {
                    return SdmxVersion.SDMX_3_0;
                }
                // SDMX XML 3.0.0
                if (accept.equals(MediaType.valueOf(SDMX_XML_3_0_0_VALUE))) {
                    return SdmxVersion.SDMX_3_0;
                }
                // SDMX JSON 2.0.0 is used for SDMX 3.0 data format
                if ("2.0.0".equals(versionParam) && subtype.contains("json")) {
                    return SdmxVersion.SDMX_3_0;
                }
                // SDMX JSON 1.0.0 is used for SDMX 2.1 data format
                if ("1.0.0".equals(versionParam) && subtype.contains("json")) {
                    return SdmxVersion.SDMX_2_1;
                }
                // SDMX CSV 2.0.0 is used for SDMX 3.0
                if ("2.0.0".equals(versionParam) && subtype.contains("csv")) {
                    return SdmxVersion.SDMX_3_0;
                }

                // SDMX 2.1 specific media types
                // Explicit version 2.1
                if ("2.1".equals(versionParam)) {
                    return SdmxVersion.SDMX_2_1;
                }
                // Draft JSON 2.1
                if (accept.equals(MediaType.valueOf(DRAFT_JSON_2_1_VALUE))) {
                    return SdmxVersion.SDMX_2_1;
                }
                // SDMX CSV 1.0.0 is typically used for SDMX 2.1
                if ("1.0.0".equals(versionParam) && subtype.contains("csv")) {
                    return SdmxVersion.SDMX_2_1;
                }

                // If we got here, it's an SDMX type but unsupported version
                // Only add to unsupported list if version parameter is present but not recognized
                if (versionParam != null) {
                    unsupportedSdmxTypes.add(accept.toString());
                }
            }
        }

        if (!unsupportedSdmxTypes.isEmpty()) {
            throw new UnsupportedSdmxVersionException(
                    String.format("Unsupported SDMX version in Accept header: %s. Supported versions: 2.1, 3.0.0",
                            String.join(", ", unsupportedSdmxTypes))
            );
        }

        if (hasGenericMediaType) {
            return SdmxVersion.SDMX_3_0;
        }

        throw new UnsupportedSdmxVersionException(
                String.format("Unsupported SDMX version in Accept header: %s. Supported versions: 2.1, 3.0.0", acceptHeader)
        );
    }

}
