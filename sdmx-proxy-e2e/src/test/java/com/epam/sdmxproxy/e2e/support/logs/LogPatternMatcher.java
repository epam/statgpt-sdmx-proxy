package com.epam.sdmxproxy.e2e.support.logs;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches ERROR/Exception patterns in logs.
 * Loads allow-list from properties file and returns violations (patterns not in allow-list).
 */
@Slf4j
public class LogPatternMatcher {

    private static final String ALLOWED_PATTERNS_RESOURCE = "log-patterns/allowed-errors.properties";
    private static final String PATTERN_PREFIX = "allowed.pattern.";

    private final List<Pattern> allowedPatterns;
    private final List<Pattern> errorPatterns;

    public LogPatternMatcher() {
        this.allowedPatterns = loadAllowedPatterns();
        this.errorPatterns = initializeErrorPatterns();
    }

    /**
     * Checks log lines for ERROR/Exception patterns and returns violations.
     *
     * @param logLines List of log lines to check
     * @return List of violations (log lines matching error patterns but not in allow-list)
     */
    public List<String> findViolations(List<String> logLines) {
        List<String> violations = new ArrayList<>();

        for (String line : logLines) {
            if (matchesErrorPattern(line) && !isAllowed(line)) {
                violations.add(line);
            }
        }

        return violations;
    }

    /**
     * Checks if a log line matches any error pattern.
     *
     * @param logLine Log line to check
     * @return true if the line matches an error pattern
     */
    public boolean matchesErrorPattern(String logLine) {
        if (logLine == null || logLine.isEmpty()) {
            return false;
        }

        String upperLine = logLine.toUpperCase();
        return errorPatterns.stream().anyMatch(pattern -> pattern.matcher(upperLine).find());
    }

    /**
     * Checks if a log line matches any allowed pattern.
     *
     * @param logLine Log line to check
     * @return true if the line matches an allowed pattern
     */
    public boolean isAllowed(String logLine) {
        if (logLine == null || logLine.isEmpty()) {
            return true; // Empty lines are allowed
        }

        return allowedPatterns.stream().anyMatch(pattern -> pattern.matcher(logLine).find());
    }

    /**
     * Loads allowed patterns from the properties file.
     *
     * @return List of compiled Pattern objects
     */
    private List<Pattern> loadAllowedPatterns() {
        List<Pattern> patterns = new ArrayList<>();

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            InputStream inputStream = classLoader.getResourceAsStream(ALLOWED_PATTERNS_RESOURCE);

            if (inputStream == null) {
                log.warn("Allowed patterns file not found: {}. No patterns will be allowed.", ALLOWED_PATTERNS_RESOURCE);
                return patterns;
            }

            Properties properties = new Properties();
            properties.load(inputStream);

            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(PATTERN_PREFIX)) {
                    String patternString = properties.getProperty(key);
                    try {
                        Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
                        patterns.add(pattern);
                        log.debug("Loaded allowed pattern: {} = {}", key, patternString);
                    } catch (PatternSyntaxException e) {
                        log.error("Invalid pattern syntax in {}: {}", key, patternString, e);
                    }
                }
            }

            log.info("Loaded {} allowed error patterns", patterns.size());

        } catch (IOException e) {
            log.error("Failed to load allowed patterns from {}", ALLOWED_PATTERNS_RESOURCE, e);
        }

        return patterns;
    }

    /**
     * Initializes common error patterns to match.
     *
     * @return List of compiled Pattern objects for ERROR and Exception patterns
     */
    private List<Pattern> initializeErrorPatterns() {
        List<Pattern> patterns = new ArrayList<>();

        // Common error patterns
        patterns.add(Pattern.compile(".*ERROR.*", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile(".*Exception.*", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile(".*Error.*", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile(".*FATAL.*", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile(".*Failed.*", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile(".*Failure.*", Pattern.CASE_INSENSITIVE));

        return patterns;
    }
}
