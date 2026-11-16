package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.exception.UnexpectedStateException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.ItemBean;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrites a match-all data key as an OR over every code of one dimension -- see design 043,
 * {@code R2}.
 * <p>
 * Statistics Canada rejects {@code all} with 400 and an all-empty key with 404, but accepts any key
 * with one non-empty position. An OR over every code of one dimension selects the same series as
 * the match-all key, so the response is unchanged. The dimension with the smallest codelist keeps
 * the URL shortest.
 */
@Service
public class MatchAllKeyExpander {

    private static final String WILDCARD = "*";

    private static final String OR = "+";

    private static final String SEPARATOR = ".";

    /**
     * Returns the SDMX 3.0 key {@code "*.2+3+1.*"} for a DSD whose smallest enumerated, non-time
     * dimension is the second one with codes 2, 3, and 1 in codelist order. Ties go to the earlier
     * dimension. {@code beans} come from a {@code dataflow ... descendants} query, so they hold
     * the DSD and its codelists.
     */
    public String expand(SdmxBeans beans) {
        DataStructureBean dsd = beans.getDataStructures().stream().findFirst().orElseThrow(() -> new UnexpectedStateException("Cannot expand a match-all key: the structure holds no DSD"));
        List<DimensionBean> dimensions = dsd.getDimensionList().getDimensions().stream().filter(dimension -> !dimension.isTimeDimension()).toList();

        int chosen = -1;
        List<String> chosenCodes = List.of();
        for (int i = 0; i < dimensions.size(); i++) {
            List<String> codes = codes(dimensions.get(i), beans);
            if (!codes.isEmpty() && (chosen < 0 || codes.size() < chosenCodes.size())) {
                chosen = i;
                chosenCodes = codes;
            }
        }
        if (chosen < 0) {
            throw new UnexpectedStateException("Cannot expand a match-all key for " + dsd.getUrn() + ": no non-time dimension has an enumerated codelist in the structure");
        }

        List<String> positions = new ArrayList<>();
        for (int i = 0; i < dimensions.size(); i++) {
            positions.add(i == chosen ? String.join(OR, chosenCodes) : WILDCARD);
        }
        return String.join(SEPARATOR, positions);
    }

    private List<String> codes(DimensionBean dimension, SdmxBeans beans) {
        RepresentationBean representation = dimension.getRepresentation();
        ICrossReferenceBean<?> codelistRef = representation != null ? representation.getRepresentation() : null;
        if (codelistRef == null) {
            return List.of();
        }
        Optional<CodelistBean> codelist = beans.getCodelists().stream().filter(candidate -> codelistRef.getReference().isMatch(candidate)).findFirst();
        return codelist.map(found -> found.getItems().stream().map(ItemBean::getId).toList()).orElse(List.of());
    }
}
