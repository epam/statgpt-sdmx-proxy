package com.epam.sdmxproxy.services.misc;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;

import java.util.List;

/**
 * Service to retrieve dimension IDs from a dataflow or data structure definition.
 *
 * <p>Dimension IDs are returned in the order the DSD declares them, not sorted. Callers that
 * build a positional SDMX 2.1 key depend on this: a key is read by position, so any other
 * ordering silently assigns each value to the wrong dimension.
 */
public interface DimensionService {

    /**
     * Gets dimension IDs for a dataflow, in DSD-declared order.
     *
     * @param sdmxBeans  The SDMX beans containing the dataflow and data structure
     * @param agencyId   The agency ID
     * @param resourceId The resource ID
     * @param version    The version
     * @return Dimension IDs in DSD-declared order
     */
    List<String> getDimensionIds(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version);

    /**
     * Gets dimension IDs from a data structure definition, in DSD-declared order.
     *
     * @param sdmxBeans The SDMX beans containing the data structure
     * @param agency    The agency ID
     * @param id        The data structure ID
     * @param version   The version
     * @return Dimension IDs in DSD-declared order
     */
    List<String> getDimensionIdsFromDsd(SdmxBeans sdmxBeans, String agency, String id, String version);

    String getTimeDimensionId(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version);
}
