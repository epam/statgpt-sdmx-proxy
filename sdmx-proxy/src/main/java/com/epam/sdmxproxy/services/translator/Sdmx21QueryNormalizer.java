package com.epam.sdmxproxy.services.translator;

/**
 * Translates SDMX 3.0 query grammar into the SDMX 2.1 forms an older registry accepts.
 * <p>
 * The proxy's public interface is always SDMX 3.0, so a client always sends 3.0 spellings; a 2.1
 * registry rejects most of them. Every rule here is idempotent, which is what makes it safe to apply
 * both in {@code QueryTranslatorImpl} and again defensively at the adapter boundary.
 * <p>
 * All rules are established empirically against OECD's NSI Web Service v8.19.8.0 -- no SDMX-REST 2.1
 * specification is available in this workspace -- and must not be restated as spec requirements.
 */
public interface Sdmx21QueryNormalizer {

    /**
     * SDMX 3.0 data/availability key to SDMX 2.1. {@code null}, {@code ""}, {@code "*"} and an
     * all-wildcard positional key all become {@code "all"}; a per-position {@code "*"} becomes an
     * empty position, so {@code "USA.A.*.*.*"} becomes {@code "USA.A...."}. Value-level OR is
     * preserved: {@code "A.B+C.*"} becomes {@code "A.B+C."}. Idempotent.
     */
    String toKey(String key);

    /**
     * SDMX 3.0 availability componentId to SDMX 2.1. {@code null} and {@code "*"} become
     * {@code "all"}; every other value -- including a comma-joined list -- is passed through
     * unchanged. Idempotent.
     * <p>
     * A comma-joined list is deliberately not collapsed: SDMX 3.0 allows multiple componentIds, so
     * rewriting a client's two-dimension request to {@code "all"} would silently widen it. The only
     * comma-joined values the proxy itself produces are fixed at their source instead.
     */
    String toComponentId(String componentId);

    /**
     * SDMX 3.0 {@code references} to SDMX 2.1. {@code "none"} becomes {@code null} so Feign drops the
     * query parameter entirely; every other value is passed through. Idempotent.
     * <p>
     * {@code none} is the documented default, so omitting the parameter is behaviour-preserving.
     * OECD's NSI answers {@code references=none} on availability with HTTP 500.
     */
    String toReferences(String references);

    /**
     * SDMX 3.0 path slot (agency / resourceID / version) to SDMX 2.1, for the 2.1 {@code flowRef}.
     * {@code "*"} and {@code null} become {@code "all"}; everything else is passed through.
     * Idempotent.
     * <p>
     * This is the OUTBOUND direction. Do not confuse it with
     * {@link QueryTranslator#normalizePathSlot}, which is the inbound direction
     * ({@code "all"} to {@code "*"}) and would make things worse here.
     */
    String toSdmx21PathSlot(String slot);
}
