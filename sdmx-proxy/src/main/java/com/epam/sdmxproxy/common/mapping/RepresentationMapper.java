package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.BaseTextFormatRepresentationImpl;
import com.epam.jsdmx.infomodel.sdmx30.EnumeratedRepresentationImpl;
import com.epam.jsdmx.infomodel.sdmx30.FacetValueType;
import com.epam.jsdmx.infomodel.sdmx30.Representation;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.base.TextFormatBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import lombok.RequiredArgsConstructor;


@RequiredArgsConstructor
public class RepresentationMapper {

    private final ReferenceMapper referenceMapper;

    public Representation map(RepresentationBean representation) {
        if (representation == null) {
            return null;
        }
        final ICrossReferenceBean<?> codelistRef = representation.getRepresentation();
        if (codelistRef != null) {
            return new EnumeratedRepresentationImpl(referenceMapper.mapMaintainable(codelistRef, StructureClassImpl.CODELIST));
        }

        final TextFormatBean textFormat = representation.getTextFormat();
        if (textFormat != null) {
            return new BaseTextFormatRepresentationImpl(mapType(textFormat));
        }

        throw new IllegalArgumentException("Unknown representation type: " + representation);
    }

    private FacetValueType mapType(TextFormatBean textFormat) {
        return FacetValueType.valueOf(textFormat.getTextType().name());
    }

}
