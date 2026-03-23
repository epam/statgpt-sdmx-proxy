package com.epam.sdmxproxy.e2e.support.logs;

import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.container.SdmxProxyContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JUnit 5 extension that persists container logs to disk for post-run analysis.
 * <p>
 * Produces two kinds of report files:
 * <ul>
 *   <li><b>Full container log</b> ({@code container.log}) — written once after all test classes finish</li>
 *   <li><b>Per-failure log segments</b> ({@code failures/{Class}_{method}.log}) — the slice of
 *       container output that was emitted while the failing test was running</li>
 * </ul>
 * <p>
 * Output directory is resolved from the system property {@code e2e.logs.dir},
 * falling back to {@code build/reports/e2e-logs}.
 * <p>
 * Usage:
 * <pre>
 * {@code @ExtendWith({ContainerFixture.class, ContainerLogReporter.class})}
 * class MyE2ETest {
 *     // tests...
 * }
 * </pre>
 */
@Slf4j
public class ContainerLogReporter implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(ContainerLogReporter.class);
    private static final String ACTIVE_CLASSES_COUNT_KEY = "activeClassesCount";
    private static final String LOG_OFFSET_KEY = "logOffset";

    private static final String DEFAULT_LOGS_DIR = "build/reports/e2e-logs";
    private static final String FAILURES_SUBDIR = "failures";
    private static final String FULL_LOG_FILENAME = "container.log";

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── lifecycle callbacks ──────────────────────────────────────────────

    private static Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write log report to {}", path, e);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        Store store = getStore(context);

        Integer activeCount = store.get(ACTIVE_CLASSES_COUNT_KEY, Integer.class);
        if (activeCount == null) {
            activeCount = 0;
        }
        activeCount++;
        store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Record current log length so we can extract the delta after the test
        int currentLength = getContainerLogsRaw().length();
        getStore(context).put(LOG_OFFSET_KEY, currentLength);
    }

    // ── internal helpers ─────────────────────────────────────────────────

    @Override
    public void afterEach(ExtensionContext context) {
        if (context.getExecutionException().isEmpty()) {
            return; // test passed — nothing to save
        }

        String className = context.getRequiredTestClass().getSimpleName();
        String methodName = context.getRequiredTestMethod().getName();
        String failureMessage = context.getExecutionException()
                .map(Throwable::getMessage)
                .orElse("(no message)");

        String allLogs = getContainerLogsRaw();
        Integer offset = getStore(context).get(LOG_OFFSET_KEY, Integer.class);
        String segment = (offset != null && offset < allLogs.length())
                ? allLogs.substring(offset)
                : allLogs;

        // Build the failure report content
        StringBuilder report = new StringBuilder();
        report.append("# Failed test: ").append(className).append('.').append(methodName).append('\n');
        report.append("# Timestamp:   ").append(LocalDateTime.now().format(TIMESTAMP_FMT)).append('\n');
        report.append("# Failure:     ").append(failureMessage).append('\n');
        report.append("# ").append("-".repeat(72)).append('\n');
        report.append('\n');
        report.append(segment);

        String fileName = className + "_" + methodName + ".log";
        Path failuresDir = getOutputDir().resolve(FAILURES_SUBDIR);
        writeFile(failuresDir.resolve(fileName), report.toString());

        log.info("Saved failure log segment for {}.{} to {}", className, methodName, failuresDir.resolve(fileName));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Store store = getStore(context);

        Integer activeCount = store.get(ACTIVE_CLASSES_COUNT_KEY, Integer.class);
        if (activeCount != null && activeCount > 0) {
            activeCount--;
            store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
        } else {
            activeCount = 0;
            store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
        }

        // Save the full container log once all test classes have finished
        if (activeCount == 0) {
            saveFullContainerLog();
        }
    }

    private void saveFullContainerLog() {
        String logs = getContainerLogsRaw();
        if (logs.isEmpty()) {
            log.info("No container logs to save");
            return;
        }

        Path outputFile = getOutputDir().resolve(FULL_LOG_FILENAME);
        writeFile(outputFile, logs);
        log.info("Saved full container log ({} chars) to {}", logs.length(), outputFile);
    }

    /**
     * Retrieves the raw container log string (stdout + stderr).
     */
    private String getContainerLogsRaw() {
        try {
            SdmxProxyContainer container = ContainerFixture.getContainer();
            if (container == null || !container.isRunning()) {
                log.warn("Container not available for log retrieval");
                return "";
            }
            String logs = container.getLogs();
            return logs != null ? logs : "";
        } catch (Exception e) {
            log.error("Failed to retrieve container logs", e);
            return "";
        }
    }

    private Path getOutputDir() {
        String dir = System.getProperty("e2e.logs.dir", DEFAULT_LOGS_DIR);
        return Path.of(dir);
    }
}
