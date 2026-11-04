package com.epam.sdmxproxy.services.adapter.statcan;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naming rules for Statistics Canada artefacts -- see design 043.
 * <p>
 * Table {@code 17100005} is published as dataflow {@code DF_17100005} with DSD
 * {@code Data_Structure_17100005}. Every bundle names its codelists {@code CL_{dimension}} and its
 * concept scheme {@code CONCEPTS}, whatever the table, so the proxy renames them to
 * {@code CL_17100005_{dimension}} and {@code CONCEPTS_17100005}. A renamed ID carries its table
 * number, which is how a request is mapped back to its bundle.
 */
@Service
public class StatCanIdentifiers {

    public static final String AGENCY = "StatCan";

    public static final String VERSION = "1.0";

    private static final String TABLE = "(\\d{8})";

    private static final String CODELIST_PREFIX = "CL_";

    private static final String CONCEPT_SCHEME_ID = "CONCEPTS";

    /**
     * Case-insensitive: sdmx-core upper-cases every maintainable ID it reads
     * ({@code MaintainableBeanImpl.setId}), so the proxy publishes {@code DATA_STRUCTURE_17100005}
     * and {@code CL_17100005_GEOGRAPHY}, and clients request those spellings back.
     */
    private static final Map<String, Pattern> PATTERNS = Map.of(
            "dataflow", Pattern.compile("DF_" + TABLE, Pattern.CASE_INSENSITIVE),
            "datastructure", Pattern.compile("Data_Structure_" + TABLE, Pattern.CASE_INSENSITIVE),
            "codelist", Pattern.compile(CODELIST_PREFIX + TABLE + "_.+", Pattern.CASE_INSENSITIVE),
            "conceptscheme", Pattern.compile(CONCEPT_SCHEME_ID + "_" + TABLE, Pattern.CASE_INSENSITIVE)
    );

    /**
     * The table an artefact ID belongs to, or empty when the ID does not follow the convention for
     * its structure type -- for example a bare {@code CL_Geography}, which is ambiguous.
     */
    public Optional<String> tableOf(String structureType, String id) {
        Pattern pattern = structureType != null ? PATTERNS.get(structureType.toLowerCase()) : null;
        if (pattern == null || id == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(id);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public String dataflowIdOf(String table) {
        return "DF_" + table;
    }

    public String dsdIdOf(String table) {
        return "Data_Structure_" + table;
    }

    /** {@code CL_Geography} in table {@code 17100005} becomes {@code CL_17100005_Geography}. */
    public String renamedCodelistId(String table, String id) {
        if (id.startsWith(CODELIST_PREFIX + table + "_")) {
            return id;
        }
        String suffix = id.startsWith(CODELIST_PREFIX) ? id.substring(CODELIST_PREFIX.length()) : id;
        return CODELIST_PREFIX + table + "_" + suffix;
    }

    /** {@code CONCEPTS} in table {@code 17100005} becomes {@code CONCEPTS_17100005}. */
    public String renamedConceptSchemeId(String table, String id) {
        if (id.equals(CONCEPT_SCHEME_ID + "_" + table)) {
            return id;
        }
        return id + "_" + table;
    }
}
