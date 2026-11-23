package com.epam.sdmxproxy.services.preheat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dataflow listed in the preheat configuration, in the form {@code AGENCY:ID(VERSION)} that
 * StatGPT admin displays. See design 044.
 */
public record DataflowReference(String agency, String id, String version) {

    private static final Pattern PATTERN = Pattern.compile("^([^:()]+):([^:()]+)\\(([^:()]+)\\)$");

    /**
     * @throws IllegalArgumentException when {@code reference} doesn't have the form {@code AGENCY:ID(VERSION)}
     */
    public static DataflowReference parse(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException("Preheat dataflow reference must not be null");
        }
        Matcher matcher = PATTERN.matcher(reference.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Preheat dataflow '" + reference + "' must have the form AGENCY:ID(VERSION)");
        }
        return new DataflowReference(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    @Override
    public String toString() {
        return agency + ":" + id + "(" + version + ")";
    }
}
