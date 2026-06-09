package com.epam.sdmxproxy.configuration.data;

/**
 * Canonical SDMX media-type strings. Single source for the literals used by both
 * {@link SdmxFormat} and the controllers' {@code produces} / {@code @Content} annotations
 * (which require compile-time String constants and cannot call enum getters).
 *
 * <p>Holds SDMX vendor types only (the {@code application/vnd.sdmx.*} strings). The generic
 * media types (wildcard, {@code text/json}, {@code application/csv}, {@code text/csv}) stay
 * as constants on {@code SdmxMediaTypeResolver} in the main module.
 */
public final class SdmxMediaTypes {

    // ===== DATA =====
    public static final String DATA_JSON_1_0_0 = "application/vnd.sdmx.data+json;version=1.0.0";
    public static final String DATA_JSON_2_0_0 = "application/vnd.sdmx.data+json; version=2.0.0";
    public static final String DATA_JSON_DRAFT_2_1 = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";
    public static final String DATA_CSV_1_0_0 = "application/vnd.sdmx.data+csv;version=1.0.0";
    public static final String DATA_CSV_2_0_0 = "application/vnd.sdmx.data+csv;version=2.0.0";
    public static final String DATA_XML_GENERIC_2_1 = "application/vnd.sdmx.genericdata+xml;version=2.1";
    public static final String DATA_XML_STRUCTURE_SPECIFIC_2_1 = "application/vnd.sdmx.structurespecificdata+xml;version=2.1";
    public static final String DATA_XML_3_0_0 = "application/vnd.sdmx.data+xml;version=3.0.0";
    public static final String DATA_XML_GENERIC_TIME_SERIES_2_1 = "application/vnd.sdmx.generictimeseriesdata+xml;version=2.1";
    public static final String DATA_XML_STRUCTURE_SPECIFIC_TIME_SERIES_2_1 = "application/vnd.sdmx.structurespecifictimeseriesdata+xml;version=2.1";

    // ===== STRUCTURE =====
    public static final String STRUCTURE_JSON_2_0_0 = "application/vnd.sdmx.structure+json; version=2.0.0";
    public static final String STRUCTURE_JSON_1_0_0 = "application/vnd.sdmx.structure+json;version=1.0.0";
    public static final String STRUCTURE_XML_2_1 = "application/vnd.sdmx.structure+xml;version=2.1";
    public static final String STRUCTURE_XML_3_0_0 = "application/vnd.sdmx.structure+xml;version=3.0.0";

    // ===== SCHEMA =====
    public static final String SCHEMA_XML_2_1 = "application/vnd.sdmx.schema+xml;version=2.1";
    public static final String SCHEMA_XML_3_0_0 = "application/vnd.sdmx.schema+xml;version=3.0.0";

    // ===== METADATA =====
    public static final String METADATA_JSON_2_0_0 = "application/vnd.sdmx.metadata+json;version=2.0.0";
    public static final String METADATA_XML_3_0_0 = "application/vnd.sdmx.metadata+xml;version=3.0.0";
    public static final String METADATA_CSV_1_0_0 = "application/vnd.sdmx.metadata+csv;version=1.0.0";
    public static final String METADATA_XML_GENERIC_2_1 = "application/vnd.sdmx.genericmetadata+xml;version=2.1";
    public static final String METADATA_XML_STRUCTURE_SPECIFIC_2_1 = "application/vnd.sdmx.structurespecificmetadata+xml;version=2.1";

    private SdmxMediaTypes() {
    }
}
