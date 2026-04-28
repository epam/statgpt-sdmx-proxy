package com.epam.sdmxproxy.services.limit;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

/**
 * Resolves the codelist size for a dimension from {@link SdmxBeans} without issuing any
 * registry call. Used by the limit-emulation TC fast-path to estimate cube size before
 * deciding whether an availability probe is warranted.
 * <p>
 * Returns {@link OptionalInt#empty()} when the size cannot be determined (dimension has no
 * representation, no enumerated codelist, or the referenced codelist is absent from
 * {@code beans}); the caller falls back to issuing a probe in that case.
 */
@Component
public class CodelistSizeResolver {

    public OptionalInt resolveSize(DimensionBean dimension, SdmxBeans beans) {
        if (dimension == null || beans == null) {
            return OptionalInt.empty();
        }
        RepresentationBean representation = dimension.getRepresentation();
        if (representation == null) {
            return OptionalInt.empty();
        }
        ICrossReferenceBean<?> codelistRef = representation.getRepresentation();
        if (codelistRef == null) {
            return OptionalInt.empty();
        }
        for (CodelistBean codelist : beans.getCodelists()) {
            if (codelistRef.getReference().isMatch(codelist)) {
                return OptionalInt.of(codelist.getItems().size());
            }
        }
        return OptionalInt.empty();
    }
}
