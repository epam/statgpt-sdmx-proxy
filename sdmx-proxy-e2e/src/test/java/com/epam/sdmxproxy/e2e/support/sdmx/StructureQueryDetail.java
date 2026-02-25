package com.epam.sdmxproxy.e2e.support.sdmx;

import lombok.Getter;

@Getter
public enum StructureQueryDetail {

    FULL("full"),
    ALL_STUBS("allstubs"),
    REFERENCED_COMPLETE_STUBS("referencecompletestubs"),
    ALL_COMPLETE_STUBS("allcompletestubs"),
    REFERENCE_PARTIAL("referencepartial"),
    REFERENCED_STUBS("referencestubs"),
    REFERENCED_COMPLETESTUBS("referencecompletestubs"),
    RAW("raw"),
    PARTIAL_RAW("partialraw");


    private final String value;

    StructureQueryDetail(String name) {
        this.value = name;
    }

}
