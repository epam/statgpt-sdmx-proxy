package com.epam.sdmxproxy.services.preheat;

import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheKeyGenerator;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * Writes split ready responses with the conversion step that {@code getStructuresConversion}
 * runs for a live point request. See design 044, "Why the split matches the live response".
 */
@Service
@RequiredArgsConstructor
public class DescendantsSplitterImpl implements DescendantsSplitter {

    private final StreamingStructureConversionService conversionService;
    private final CacheService cacheService;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;

    @Override
    public String readyResponseKey(TranslatedStructureQuery pointQuery) {
        return CacheKeyGenerator.generateResponseKey(pointQuery, pointQuery.getContentType(), Collections.emptyMap(), sdmx21QueryNormalizer);
    }

    @Override
    public void store(TranslatedStructureQuery pointQuery, MaintainableBean artefact, HeaderBean header) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.convert(new SdmxBeansImpl(header, List.of(artefact)), out, pointQuery.getContentType());
        cacheService.putReadyResponse(readyResponseKey(pointQuery), out.toByteArray());
    }
}
