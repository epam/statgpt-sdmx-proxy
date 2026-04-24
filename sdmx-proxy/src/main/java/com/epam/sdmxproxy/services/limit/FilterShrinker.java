package com.epam.sdmxproxy.services.limit;

/**
 * Computes the next shrink step for the heuristic limit-emulation algorithm.
 * Stateless: one call = one decision. The orchestrator drives the loop.
 * <p>
 * The current filter map is intentionally NOT a parameter: the decision depends only on the
 * (re-probed) projection and the target. Any state the client established in its original
 * filter is already reflected in the projection the registry returned.
 */
public interface FilterShrinker {

    /**
     * Picks a dimension to shrink and the number of values to retain on that dimension.
     *
     * @param projection          current availability projection
     * @param targetUpperBound    {@code floor(N * tolerance)}
     * @param timeDimensionId     id of the time dimension (never chosen; may be null)
     * @return decision or {@link ShrinkDecision#none()} if no productive shrink is possible
     */
    ShrinkDecision decide(
            AvailabilityProjection projection,
            long targetUpperBound,
            String timeDimensionId
    );
}
