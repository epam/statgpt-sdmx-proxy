package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

public interface AdapterRouter {
    StreamingResponseBody getStructures(TranslatedStructureQuery query);

    SdmxBeans getSdmxBeans(TranslatedStructureQuery query);

    /**
     * Fan-out structure request: fetch + parse each per-registry query in parallel, merge the
     * resulting {@link SdmxBeans}, and stream the serialized response. The merged byte buffer
     * is written to the ready-response cache under {@code responseKey} only if every leg
     * succeeded; partial responses are still streamed but not cached.
     *
     * @param queries     non-empty list of per-registry queries; the controller is responsible
     *                    for short-circuiting empty fan-outs
     * @param responseKey cache key for the merged response (computed via
     *                    {@link com.epam.sdmxproxy.services.cache.CacheKeyGenerator#generateFanOutResponseKey})
     */
    StreamingResponseBody getStructuresWithFanOut(List<TranslatedStructureQuery> queries, String responseKey);

    StreamingResponseBody getData(TranslatedDataQuery query);

    StreamingResponseBody getAvailability(TranslatedAvailabilityQuery query);
}
