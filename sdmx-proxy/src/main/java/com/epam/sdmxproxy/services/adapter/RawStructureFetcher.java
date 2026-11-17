package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;

/**
 * The single entry point for structure bytes from a registry. Returns the registry's response
 * verbatim -- no fixtures -- served from the raw-structures cache when possible, and coalesced so
 * that concurrent callers for one request share one upstream call. See design 042.
 */
public interface RawStructureFetcher {

    /**
     * @param query translated structure query
     * @return the registry's bytes, or an empty array when the registry returned no body
     */
    byte[] fetch(TranslatedStructureQuery query);
}
