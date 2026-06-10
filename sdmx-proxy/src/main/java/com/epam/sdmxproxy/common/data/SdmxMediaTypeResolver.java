package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.exception.UnsupportedMediaTypeParameterException;
import com.epam.sdmxproxy.exception.UnsupportedSdmxVersionException;
import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP content-negotiation helper for SDMX media types. The recognised set of SDMX vendor types is
 * derived from {@link SdmxFormat#values()} (the single source of truth for format strings); the
 * generic / wildcard media types (wildcard, {@code text/json}, {@code application/csv},
 * {@code text/csv}, version-less {@code application/vnd.sdmx.data+csv}) have no enum member and stay
 * as constants here.
 */
@UtilityClass
public class SdmxMediaTypeResolver {

    public static final String ANY = "*/*";
    public static final String TEXT_JSON_VALUE = "text/json";
    public static final String APPLICATION_CSV_VALUE = "application/csv";
    public static final String TEXT_CSV_VALUE = "text/csv";
    public static final String SDMX_CSV_VALUE = "application/vnd.sdmx.data+csv";

    static final Set<MediaType> JSON_MEDIA_TYPES = buildMediaTypeSet("json", MediaType.valueOf(TEXT_JSON_VALUE), MediaType.APPLICATION_JSON);
    static final Set<MediaType> XML_MEDIA_TYPES = buildMediaTypeSet("xml", MediaType.APPLICATION_XML);
    static final Set<MediaType> CSV_MEDIA_TYPES = buildMediaTypeSet("csv", MediaType.valueOf(APPLICATION_CSV_VALUE), MediaType.valueOf(TEXT_CSV_VALUE), MediaType.valueOf(SDMX_CSV_VALUE));

    private static final Set<String> CSV_ONLY_PARAMETERS = Set.of("labels", "timeformat", "keys");

    /**
     * Builds a recognised-media-type set from every {@link SdmxFormat} whose subtype contains the
     * given token (e.g. {@code json} / {@code xml} / {@code csv}), plus the supplied generic types.
     */
    private static Set<MediaType> buildMediaTypeSet(String subtypeToken, MediaType... genericTypes) {
        Set<MediaType> mediaTypes = Arrays.stream(SdmxFormat.values())
                .map(format -> MediaType.valueOf(format.getContentType()))
                .filter(mediaType -> mediaType.getSubtype() != null && mediaType.getSubtype().toLowerCase().contains(subtypeToken))
                .collect(Collectors.toCollection(HashSet::new));
        mediaTypes.addAll(Arrays.asList(genericTypes));
        return Collections.unmodifiableSet(mediaTypes);
    }

    public static boolean isMatch(MediaType m1, MediaType m2) {
        return m1.getType().equals(m2.getType())
                && m1.getSubtype().equals(m2.getSubtype());
    }

    /**
     * True when {@code mediaType} is one of the recognised JSON media types (SDMX-JSON variants or
     * {@code application/json}). Null-safe.
     */
    public static boolean isJson(MediaType mediaType) {
        return mediaType != null && JSON_MEDIA_TYPES.stream().anyMatch(json -> isMatch(json, mediaType));
    }

    /**
     * True when {@code mediaType} is an SDMX-ML XML media type carrying {@code version=2.1}
     * (e.g. {@code application/vnd.sdmx.structure+xml;version=2.1}). Null-safe.
     */
    public static boolean isXmlV21(MediaType mediaType) {
        return mediaType != null
                && mediaType.getSubtype() != null
                && mediaType.getSubtype().toLowerCase().contains("xml")
                && "2.1".equals(mediaType.getParameter("version"));
    }

    /**
     * True when {@code mediaType} is an SDMX-ML XML media type carrying {@code version=3.0.0}
     * (e.g. {@code application/vnd.sdmx.structure+xml;version=3.0.0}). Null-safe.
     */
    public static boolean isXmlV30(MediaType mediaType) {
        return mediaType != null
                && mediaType.getSubtype() != null
                && mediaType.getSubtype().toLowerCase().contains("xml")
                && "3.0.0".equals(mediaType.getParameter("version"));
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
                    return MediaType.valueOf(SdmxFormat.CSV_DATA_2_0_0.getContentType());
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
        validateCsvParameters(mediaType);

        return MediaTypeParseResult.builder()
                .mediaType(mediaType)
                .sdmxVersion(sdmxVersion)
                .containsCsvParameters(containsCsvParameters(mediaType))
                .build();
    }

    private static boolean containsCsvParameters(MediaType mediaType) {
        if (!isCsvMediaType(mediaType)) {
            return false;
        }

        return mediaType.getParameters().keySet().stream().anyMatch(key -> CSV_ONLY_PARAMETERS.contains(key.toLowerCase()));
    }

    /**
     * Validates that CSV-specific parameters (labels, timeFormat, keys) are only present
     * on CSV media types. Throws UnsupportedMediaTypeParameterException if these parameters
     * appear on JSON or XML media types.
     */
    public static void validateCsvParameters(MediaType mediaType) {
        if (isCsvMediaType(mediaType)) {
            return; // CSV type -- parameters are valid
        }

        List<String> invalidParams = mediaType.getParameters().keySet().stream()
                .filter(key -> CSV_ONLY_PARAMETERS.contains(key.toLowerCase()))
                .toList();

        if (!invalidParams.isEmpty()) {
            throw new UnsupportedMediaTypeParameterException(
                    String.format("Parameters %s are only supported for CSV media types. Received media type: %s", invalidParams, mediaType));
        }
    }

    private static boolean isCsvMediaType(MediaType mediaType) {
        return CSV_MEDIA_TYPES.stream().anyMatch(csv -> isMatch(csv, mediaType));
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
                // Only generic (non-SDMX) types from these sets can match here,
                // since we already checked !subtype.contains("sdmx")
                if (JSON_MEDIA_TYPES.stream().anyMatch(j -> isMatch(j, accept))
                        || XML_MEDIA_TYPES.stream().anyMatch(x -> isMatch(x, accept))
                        || CSV_MEDIA_TYPES.stream().anyMatch(c -> isMatch(c, accept))) {
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
                // SDMX CSV 1.0.0 is typically used for SDMX 2.1
                if ("1.0.0".equals(versionParam) && subtype.contains("csv")) {
                    return SdmxVersion.SDMX_2_1;
                }

                // SDMX type without version parameter -- default to 3.0
                // (consistent with mapMediaType() which defaults unversioned SDMX CSV to 2.0.0)
                if (versionParam == null) {
                    return SdmxVersion.SDMX_3_0;
                }

                // If we got here, it's an SDMX type with unsupported version
                unsupportedSdmxTypes.add(accept.toString());
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
