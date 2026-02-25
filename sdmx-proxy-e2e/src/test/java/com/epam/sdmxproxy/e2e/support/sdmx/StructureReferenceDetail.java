package com.epam.sdmxproxy.e2e.support.sdmx;

import lombok.Getter;

@Getter
public enum StructureReferenceDetail {

    NONE("none"),
    PARENTS("parents"),
    PARENTS_SIBLINGS("parentsandsiblings"),
    CHILDREN("children"),
    DESCENDANTS("descendants"),
    ANCESTORS("ancestors"),
    ALL("all");

    private final String value;

    StructureReferenceDetail(String name) {
        this.value = name;
    }

}
