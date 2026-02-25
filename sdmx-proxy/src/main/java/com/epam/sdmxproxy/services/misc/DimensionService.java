package com.epam.sdmxproxy.services.misc;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.util.Set;

/**
 * Service to retrieve dimension IDs from a dataflow or data structure definition.
 */
public interface DimensionService {

    /**
     * Gets dimension IDs for a dataflow.
     *
     * @param sdmxBeans  The SDMX beans containing the dataflow and data structure
     * @param agencyId   The agency ID
     * @param resourceId The resource ID
     * @param version    The version
     * @return Set of dimension IDs
     */
    Set<String> getDimensionIds(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version);

    /**
     * Gets dimension IDs from a data structure definition.
     *
     * @param sdmxBeans The SDMX beans containing the data structure
     * @param agency    The agency ID
     * @param id        The data structure ID
     * @param version   The version
     * @return Set of dimension IDs
     */
    Set<String> getDimensionIdsFromDsd(SdmxBeans sdmxBeans, String agency, String id, String version);

    String getTimeDimensionId(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version);
}
