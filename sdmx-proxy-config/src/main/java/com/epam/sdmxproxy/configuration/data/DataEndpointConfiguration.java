package com.epam.sdmxproxy.configuration.data;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataEndpointConfiguration extends EndpointConfiguration {

    /**
     * When true, empty dimensions in the data query key are replaced with '*'.
     * Required for registries (e.g., IMF) that treat an empty dimension
     * differently from the wildcard '*' in data queries.
     * For example, ".L_T.P_F3" becomes "*.L_T.P_F3".
     */
    private boolean replaceEmptyDimensionsWithWildcard;

    /**
     * When true, a key whose every dimension position is '*' is collapsed to a single '*'.
     * Required for registries (e.g., BIS) that accept only one '*' as the match-all form
     * and return no data for a per-dimension expansion.
     * For example, "*.*.*.*" becomes "*".
     */
    private boolean mergeAllWildcardKey;

}
