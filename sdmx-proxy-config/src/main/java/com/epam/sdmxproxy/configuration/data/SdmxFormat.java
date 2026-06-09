package com.epam.sdmxproxy.configuration.data;

import lombok.Getter;

/**
 * Canonical registry of SDMX wire formats the proxy knows about. Single source of truth for
 * format names and their content-type strings (the value appears in registry configuration JSON
 * as {@code supportedFormats} / {@code defaultFormat} and drives the conversion switches).
 *
 * <p>Each member carries:
 * <ul>
 *   <li>{@code contentType} -- the canonical SDMX media-type string (from {@link SdmxMediaTypes}).
 *       For the formats that existed before design 035 the string is preserved byte-for-byte,
 *       because it is sent verbatim as the outbound {@code Accept} header to upstream registries.</li>
 *   <li>{@code sdmxVersion} -- the SDMX version this format belongs to. Intrinsic metadata,
 *       parity-tested against {@code SdmxMediaTypeResolver.extractSdmxVersion}.</li>
 * </ul>
 *
 * <p>The full SDMX matrix is enumerated for naming/recognition completeness. Whether a client may
 * request a format is governed by the controllers' {@code produces} lists; whether the proxy can
 * convert it is governed by the conversion {@code switch} arms. There is deliberately no
 * {@code supported} flag.
 */
@Getter
public enum SdmxFormat {

    // ===== DATA =====
    JSON_DATA_1_0_0(SdmxMediaTypes.DATA_JSON_1_0_0, SdmxVersion.SDMX_2_1),
    JSON_DATA_2_0_0(SdmxMediaTypes.DATA_JSON_2_0_0, SdmxVersion.SDMX_3_0),
    JSON_DATA_DRAFT_2_1(SdmxMediaTypes.DATA_JSON_DRAFT_2_1, SdmxVersion.SDMX_2_1),
    CSV_DATA_1_0_0(SdmxMediaTypes.DATA_CSV_1_0_0, SdmxVersion.SDMX_2_1),
    CSV_DATA_2_0_0(SdmxMediaTypes.DATA_CSV_2_0_0, SdmxVersion.SDMX_3_0),
    XML_DATA_3_0_0(SdmxMediaTypes.DATA_XML_3_0_0, SdmxVersion.SDMX_3_0),
    XML_GENERIC_DATA_2_1(SdmxMediaTypes.DATA_XML_GENERIC_2_1, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_DATA_2_1(SdmxMediaTypes.DATA_XML_STRUCTURE_SPECIFIC_2_1, SdmxVersion.SDMX_2_1),
    XML_GENERIC_TIME_SERIES_DATA_2_1(SdmxMediaTypes.DATA_XML_GENERIC_TIME_SERIES_2_1, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_TIME_SERIES_DATA_2_1(SdmxMediaTypes.DATA_XML_STRUCTURE_SPECIFIC_TIME_SERIES_2_1, SdmxVersion.SDMX_2_1),

    // ===== STRUCTURE =====
    JSON_STRUCTURE_2_0_0(SdmxMediaTypes.STRUCTURE_JSON_2_0_0, SdmxVersion.SDMX_3_0),
    JSON_STRUCTURE_1_0_0(SdmxMediaTypes.STRUCTURE_JSON_1_0_0, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_2_1(SdmxMediaTypes.STRUCTURE_XML_2_1, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_3_0_0(SdmxMediaTypes.STRUCTURE_XML_3_0_0, SdmxVersion.SDMX_3_0),

    // ===== SCHEMA =====
    XML_SCHEMA_2_1(SdmxMediaTypes.SCHEMA_XML_2_1, SdmxVersion.SDMX_2_1),
    XML_SCHEMA_3_0_0(SdmxMediaTypes.SCHEMA_XML_3_0_0, SdmxVersion.SDMX_3_0),

    // ===== METADATA =====
    JSON_METADATA_2_0_0(SdmxMediaTypes.METADATA_JSON_2_0_0, SdmxVersion.SDMX_3_0),
    XML_METADATA_3_0_0(SdmxMediaTypes.METADATA_XML_3_0_0, SdmxVersion.SDMX_3_0),
    CSV_METADATA_1_0_0(SdmxMediaTypes.METADATA_CSV_1_0_0, SdmxVersion.SDMX_2_1),
    XML_GENERIC_METADATA_2_1(SdmxMediaTypes.METADATA_XML_GENERIC_2_1, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_METADATA_2_1(SdmxMediaTypes.METADATA_XML_STRUCTURE_SPECIFIC_2_1, SdmxVersion.SDMX_2_1);

    private final String contentType;
    private final SdmxVersion sdmxVersion;

    SdmxFormat(String contentType, SdmxVersion sdmxVersion) {
        this.contentType = contentType;
        this.sdmxVersion = sdmxVersion;
    }
}
