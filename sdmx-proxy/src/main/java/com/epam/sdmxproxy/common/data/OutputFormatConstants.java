package com.epam.sdmxproxy.common.data;

import org.springframework.http.MediaType;

public final class OutputFormatConstants {
    public static final String CONTENT_TYPE_DRAFT_JSON_2_1_VALUE = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";
    public static final String CONTENT_TYPE_TEXT_JSON_VALUE = "text/json";
    public static final String CONTENT_TYPE_APPLICATION_CSV_VALUE = "application/csv";
    public static final String CONTENT_TYPE_TEXT_CSV_VALUE = "text/csv";
    public static final String CONTENT_TYPE_SDMX_CSV_1_0_0_VALUE = "application/vnd.sdmx.data+csv; version=1.0.0";
    public static final String CONTENT_TYPE_TEXT_CSV_WITH_LABELS_1_0_0_VALUE = "application/vnd.sdmx.data+csv;version=1.0.0;labels=both";
    public static final MediaType CONTENT_TYPE_SDMX_CSV_1_0_0 = MediaType.valueOf(CONTENT_TYPE_SDMX_CSV_1_0_0_VALUE);
    public static final MediaType CONTENT_TYPE_APPLICATION_CSV = MediaType.valueOf(CONTENT_TYPE_APPLICATION_CSV_VALUE);
    public static final MediaType CONTENT_TYPE_TEXT_CSV = MediaType.valueOf(CONTENT_TYPE_TEXT_CSV_VALUE);
    public static final MediaType CONTENT_TYPE_TEXT_CSV_WITH_LABELS_1_0_0 = MediaType.valueOf(CONTENT_TYPE_TEXT_CSV_WITH_LABELS_1_0_0_VALUE);

    private OutputFormatConstants() {
    }
}
