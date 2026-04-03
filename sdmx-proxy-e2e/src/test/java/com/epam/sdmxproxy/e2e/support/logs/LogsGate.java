package com.epam.sdmxproxy.e2e.support.logs;

import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.container.SdmxProxyContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

/**
 * JUnit 5 extension that checks logs after test suite.
 * Uses LogPatternMatcher to detect violations and fails suite if disallowed patterns found.
 * <p>
 * Usage:
 * <pre>
 * {@code @ExtendWith({ContainerFixture.class, LogsGate.class})}
 * class MyE2ETest {
 *     // tests...
 * }
 * </pre>
 */
@Slf4j
public class LogsGate implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LogsGate.class);
    private static final String LOG_MATCHER_KEY = "logMatcher";

    private final LogPatternMatcher logMatcher;

    public LogsGate() {
        this.logMatcher = new LogPatternMatcher();
    }

    private static ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Store the log matcher in the extension context
        ExtensionContext.Store store = getStore(context);
        store.put(LOG_MATCHER_KEY, logMatcher);

        log.debug("LogsGate initialized for test suite: {}", context.getDisplayName());
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = getStore(context);
        LogPatternMatcher matcher = store.get(LOG_MATCHER_KEY, LogPatternMatcher.class);

        if (matcher == null) {
            log.warn("LogPatternMatcher not found in extension context");
            return;
        }

        // Get container logs
        List<String> logLines = getContainerLogs();

        if (logLines.isEmpty()) {
            log.info("No container logs available for analysis");
            return;
        }

        // Check for violations
        List<String> violations = matcher.findViolations(logLines);

        if (!violations.isEmpty()) {
            log.error("Found {} log violations (ERROR/Exception patterns not in allow-list):", violations.size());
            for (String violation : violations) {
                log.error("  VIOLATION: {}", violation);
            }

            // Fail the test suite
            throw new AssertionError(
                    String.format(
                            "LogsGate detected %d violations in container logs. " +
                                    "Review the violations above and either fix the issues or add patterns to " +
                                    "src/test/resources/log-patterns/allowed-errors.properties if they are known benign errors.",
                            violations.size()
                    )
            );
        } else {
            log.info("LogsGate: No violations found in {} log lines", logLines.size());
        }
    }

    /**
     * Retrieves container logs for analysis.
     *
     * @return List of log lines
     */
    private List<String> getContainerLogs() {
        try {
            SdmxProxyContainer container = ContainerFixture.getContainer();
            if (container == null || !container.isRunning()) {
                log.warn("Container not available for log retrieval");
                return List.of();
            }

            String logs = container.getLogs();
            if (logs == null || logs.isEmpty()) {
                return List.of();
            }

            return logs.lines().toList();

        } catch (Exception e) {
            log.error("Failed to retrieve container logs", e);
            return List.of();
        }
    }
}
