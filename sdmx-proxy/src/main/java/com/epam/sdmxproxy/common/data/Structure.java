package com.epam.sdmxproxy.common.data;

import java.util.Objects;

public record Structure(
        String type,
        String agency,
        String id,
        String version
) {
    public Structure {
        Objects.requireNonNull(type, "structureType");
        Objects.requireNonNull(agency, "agency");
        Objects.requireNonNull(id, "id");
    }
}
