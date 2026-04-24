package com.epam.sdmxproxy.services.limit;

import lombok.Value;

import java.util.List;

/**
 * One shrink step: pick dimension {@code dimensionId} and keep only {@code retainedValues}.
 * {@link #none()} signals that no productive shrink is possible.
 */
@Value
public class ShrinkDecision {
    String dimensionId;
    List<String> retainedValues;

    public boolean isNone() {
        return dimensionId == null;
    }

    public static ShrinkDecision none() {
        return new ShrinkDecision(null, List.of());
    }
}
