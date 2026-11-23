package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;

/**
 * Stores one concept scheme or codelist from a descendants closure as the ready response to its
 * own point request. See design 044.
 */
public interface DescendantsSplitter {

    /**
     * Ready-response key of {@code pointQuery}, built exactly as {@code AdapterRouterImpl.getStructures} builds it.
     */
    String readyResponseKey(TranslatedStructureQuery pointQuery);

    /**
     * Serializes {@code artefact} as a single-artefact message in the point query's media type,
     * with {@code header} as the message header, and stores it under {@link #readyResponseKey}.
     */
    void store(TranslatedStructureQuery pointQuery, MaintainableBean artefact, HeaderBean header);
}
