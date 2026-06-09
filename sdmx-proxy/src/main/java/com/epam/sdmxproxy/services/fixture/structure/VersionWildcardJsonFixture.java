package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON fixture that normalizes non-compliant SDMX version wildcards in structure responses.
 * <p>
 * Per the SDMX 3.0 specification, when a version part contains a wildcard operator ({@code +},
 * {@code ~}, {@code *}), all subsequent version parts must be {@code 0}. Some registries (e.g., IMF)
 * return URNs with non-zero trailing parts after a wildcarded component (e.g., {@code (1.3+.1)}),
 * which causes {@code SdmxSemmanticException} during parsing.
 * <p>
 * Replacements are applied only when the version appears inside parentheses (URN context),
 * so free text in descriptions, names, or annotations is never modified.
 * <p>
 * This fixture applies two corrections:
 * <ul>
 *   <li>Major wildcard: {@code (1+.5.1)} &rarr; {@code (1+.0.0)}</li>
 *   <li>Minor wildcard: {@code (1.5+.1)} &rarr; {@code (1.5+.0)}</li>
 * </ul>
 * Patch wildcards (e.g., {@code (1.5.1+)}) require no correction since there are no subsequent parts.
 * <p>
 * No configuration parameters are required.
 */
@Slf4j
@Component
public class VersionWildcardJsonFixture implements StructureFixture {

    /**
     * Matches a 3-part version in URN context (inside parentheses) where the major part has a wildcard: e.g. {@code (1+.5.1)}
     * <p>Groups: (1) digits before wildcard, (2) wildcard char, (3) minor, (4) patch
     */
    static final Pattern MAJOR_WILDCARD_PATTERN =
            Pattern.compile("\\((\\d+)([+~*])\\.(\\d+)\\.(\\d+)\\)");

    /**
     * Matches a 3-part version in URN context (inside parentheses) where the minor part has a wildcard: e.g. {@code (1.5+.1)}
     * <p>Groups: (1) major, (2) minor digits before wildcard, (3) wildcard char, (4) patch
     */
    static final Pattern MINOR_WILDCARD_PATTERN =
            Pattern.compile("\\((\\d+)\\.(\\d+)([+~*])\\.(\\d+)\\)");

    @Override
    public StructureFixtureType getType() {
        return StructureFixtureType.VERSION_WILDCARD;
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, Map<String, String> config) {
        try {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            content = fixMajorWildcards(content);
            content = fixMinorWildcards(content);

            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to read input stream for version wildcard fix, returning original stream", e);
            return input;
        }
    }

    private String fixMajorWildcards(String content) {
        int count = 0;
        Matcher matcher = MAJOR_WILDCARD_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String minor = matcher.group(3);
            String patch = matcher.group(4);
            if (!"0".equals(minor) || !"0".equals(patch)) {
                matcher.appendReplacement(sb, "(" + matcher.group(1) + matcher.group(2) + ".0.0)");
                count++;
            }
        }
        matcher.appendTail(sb);

        if (count > 0) {
            log.debug("Fixed {} non-compliant major version wildcard(s)", count);
        }
        return sb.toString();
    }

    private String fixMinorWildcards(String content) {
        int count = 0;
        Matcher matcher = MINOR_WILDCARD_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String patch = matcher.group(4);
            if (!"0".equals(patch)) {
                matcher.appendReplacement(sb,
                        "(" + matcher.group(1) + "." + matcher.group(2) + matcher.group(3) + ".0)");
                count++;
            }
        }
        matcher.appendTail(sb);

        if (count > 0) {
            log.debug("Fixed {} non-compliant minor version wildcard(s)", count);
        }
        return sb.toString();
    }
}
