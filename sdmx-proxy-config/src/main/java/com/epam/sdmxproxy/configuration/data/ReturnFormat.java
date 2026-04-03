package com.epam.sdmxproxy.configuration.data;

import lombok.Getter;

@Getter
public enum ReturnFormat {

    JSON_1_0_0("application/vnd.sdmx.data+json;version=1.0.0"),
    JSON_2_1_DRAFT("application/vnd.sdmx.draft-sdmx-json+json; version=2.1"),
    JSON_DATA_2_0_0("application/vnd.sdmx.data+json; version=2.0.0"),
    JSON_STRUCTURE_2_0_0("application/vnd.sdmx.structure+json; version=2.0.0"),

    XML_2_1("application/vnd.sdmx.structure+xml;version=2.1"),
    XML_GENERICDATA_2_1("application/vnd.sdmx.genericdata+xml;version=2.1"),
    XML_STRUCTURE_SPECIFIC_2_1("application/vnd.sdmx.structurespecificdata+xml;version=2.1"),
    CSV_DATA_1_0_0("application/vnd.sdmx.data+csv;version=1.0.0"),
    CSV_DATA_2_0_0("application/vnd.sdmx.data+csv;version=2.0.0");

    private final String contentType;

    ReturnFormat(String contentType) {
        this.contentType = contentType;
    }
}
