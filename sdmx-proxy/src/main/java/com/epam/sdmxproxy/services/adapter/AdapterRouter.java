package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public interface AdapterRouter {
    StreamingResponseBody getStructures(TranslatedStructureQuery query);

    SdmxBeans getSdmxBeans(TranslatedStructureQuery query);

    StreamingResponseBody getData(TranslatedDataQuery query);

    StreamingResponseBody getAvailability(TranslatedAvailabilityQuery query);
}
