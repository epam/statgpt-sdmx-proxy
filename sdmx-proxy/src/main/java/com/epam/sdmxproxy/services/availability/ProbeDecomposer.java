package com.epam.sdmxproxy.services.availability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.services.availability.harvest.DimensionIds;
import com.epam.sdmxproxy.services.limit.CodelistSizeResolver;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.base.ItemBean;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * Splits an over-large probe into chunks pinned on one dimension -- see design 040.
 * <p>
 * This is a latency and resilience tool, not a correctness one: {@code detail=serieskeysonly} is
 * not subject to Eurostat's extraction-size gate (measured synchronous 200s up to 139 MB and
 * 71 s), so a broad probe succeeds -- it just takes a minute and pushes the registry's read
 * timeout. Splitting keeps every request short, which keeps the circuit breaker's latency view
 * honest.
 * <p>
 * Recombining chunks is a per-dimension set union, and that is <em>exact</em> rather than an
 * approximation. A cube region is a rectangular projection: for every dimension other than the
 * split dimension the union across chunks equals the unsplit result, and for the split dimension
 * the union of the chunk values that returned rows equals it too. There is no joint structure for
 * splitting to lose -- which is equally true of a real availability response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProbeDecomposer {

    private final CodelistSizeResolver codelistSizeResolver;

    /**
     * One chunk of a decomposed probe.
     *
     * @param dimensionId dimension the chunk pins
     * @param values      values pinned for it
     */
    public record Chunk(String dimensionId, List<String> values) {
    }

    /**
     * Picks the split dimension and chunks its codelist.
     * <p>
     * The dimension must be one the client did <em>not</em> constrain: pinning a dimension the
     * client already narrowed would either duplicate its filter or, worse, widen it.
     *
     * @return chunks to probe, or empty when no dimension is splittable (nothing unconstrained,
     *         no enumerated codelist, or a codelist too small to be worth splitting)
     */
    public Optional<List<Chunk>> plan(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityQueryCanonicalizer canonicalizer,
            int chunkSize,
            int maxChunks
    ) {
        if (chunkSize <= 0 || maxChunks <= 1) {
            return Optional.empty();
        }
        List<String> constrained = constrainedDimensionIds(query, beans, canonicalizer);

        Optional<DimensionBean> splitDimension = DimensionIds.nonTimeDimensions(beans).stream()
                .filter(dimension -> !constrained.contains(dimension.getId()))
                .filter(dimension -> codelistSizeResolver.resolveSize(dimension, beans).isPresent())
                .max(Comparator.comparingInt(dimension -> size(dimension, beans)));

        if (splitDimension.isEmpty()) {
            log.info("Probe for {} cannot be decomposed: no unconstrained dimension with an enumerated codelist",
                    describe(query));
            return Optional.empty();
        }

        DimensionBean dimension = splitDimension.get();
        List<String> codes = codes(dimension, beans);
        if (codes.size() <= chunkSize) {
            log.info("Probe for {} cannot be usefully decomposed: largest unconstrained dimension {} has {} codes",
                    describe(query), dimension.getId(), codes.size());
            return Optional.empty();
        }

        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < codes.size(); start += chunkSize) {
            chunks.add(new Chunk(
                    dimension.getId(),
                    List.copyOf(codes.subList(start, Math.min(start + chunkSize, codes.size())))
            ));
        }
        if (chunks.size() > maxChunks) {
            log.info("Probe for {} needs {} chunks on {} but the fan-out budget is {}",
                    describe(query), chunks.size(), dimension.getId(), maxChunks);
            return Optional.empty();
        }

        log.info("Decomposing the probe for {} into {} chunks of at most {} values on dimension {}",
                describe(query), chunks.size(), chunkSize, dimension.getId());
        return Optional.of(chunks);
    }

    /**
     * Dimension IDs the client narrowed, whether through the positional key or through
     * {@code c[]} filters.
     */
    private List<String> constrainedDimensionIds(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            AvailabilityQueryCanonicalizer canonicalizer
    ) {
        List<String> constrained = new ArrayList<>();
        MultiValueMap<String, String> filters = query.getFilters();
        if (filters != null) {
            filters.forEach((dimensionId, values) -> {
                if (values != null && values.stream().anyMatch(value -> value != null && !value.isBlank())) {
                    constrained.add(dimensionId);
                }
            });
        }

        String key = query.getKey();
        if (!canonicalizer.isWildcardKey(key)) {
            List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(beans);
            String[] positions = key.trim().split("\\.", -1);
            for (int i = 0; i < positions.length && i < dimensionIds.size(); i++) {
                String position = positions[i].trim();
                if (!position.isEmpty() && !AvailabilityQueryCanonicalizer.WILDCARD.equals(position)) {
                    constrained.add(dimensionIds.get(i));
                }
            }
        }
        return constrained;
    }

    /**
     * Applies a chunk to a probe's key or filters, depending on the SDMX version: SDMX 3.0 has
     * {@code c[]} filters, SDMX 2.1 has only the positional key.
     */
    public PinnedNarrowing pin(
            TranslatedAvailabilityQuery query,
            SdmxBeans beans,
            Chunk chunk,
            MultiValueMap<String, String> baseFilters
    ) {
        SdmxVersion sdmxVersion = query.getVersionConfiguration().getSdmxVersion();
        if (sdmxVersion == SdmxVersion.SDMX_3_0) {
            MultiValueMap<String, String> filters =
                    new org.springframework.util.LinkedMultiValueMap<>();
            if (baseFilters != null) {
                filters.addAll(baseFilters);
            }
            filters.put(chunk.dimensionId(), List.of(String.join(",", chunk.values())));
            return new PinnedNarrowing(query.getKey(), filters);
        }

        List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(beans);
        String[] positions = new String[dimensionIds.size()];
        String key = query.getKey();
        String[] existing = key == null || key.isBlank()
                ? new String[0]
                : key.trim().split("\\.", -1);
        for (int i = 0; i < positions.length; i++) {
            String position = i < existing.length ? existing[i].trim() : "";
            positions[i] = AvailabilityQueryCanonicalizer.WILDCARD.equals(position) ? "" : position;
        }
        int splitIndex = dimensionIds.indexOf(chunk.dimensionId());
        if (splitIndex >= 0) {
            positions[splitIndex] = String.join("+", chunk.values());
        }
        return new PinnedNarrowing(String.join(".", positions), baseFilters);
    }

    /**
     * A probe's key and filters after a chunk has been pinned into them.
     */
    public record PinnedNarrowing(String key, MultiValueMap<String, String> filters) {
    }

    private int size(DimensionBean dimension, SdmxBeans beans) {
        OptionalInt size = codelistSizeResolver.resolveSize(dimension, beans);
        return size.orElse(0);
    }

    private List<String> codes(DimensionBean dimension, SdmxBeans beans) {
        RepresentationBean representation = dimension.getRepresentation();
        if (representation == null) {
            return List.of();
        }
        ICrossReferenceBean<?> codelistRef = representation.getRepresentation();
        if (codelistRef == null) {
            return List.of();
        }
        for (CodelistBean codelist : beans.getCodelists()) {
            if (codelistRef.getReference().isMatch(codelist)) {
                List<String> codes = new ArrayList<>();
                for (ItemBean item : codelist.getItems()) {
                    codes.add(item.getId());
                }
                return codes;
            }
        }
        return List.of();
    }

    private static String describe(TranslatedAvailabilityQuery query) {
        return query.getAgencyID() + ":" + query.getResourceID() + "(" + query.getVersion() + ")";
    }
}
