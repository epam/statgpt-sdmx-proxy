package com.epam.sdmxproxy.e2e.support.container;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

/**
 * JUnit 5 extension that manages the container lifecycle for E2E tests.
 * Ensures the container starts once per test suite and provides base URL access.
 * <p>
 * Usage:
 * <pre>
 * {@code @ExtendWith(ContainerFixture.class)}
 * class MyE2ETest {
 *     {@code @Test}
 *     void testSomething() {
 *         String baseUrl = ContainerFixture.getBaseUrl();
 *         // Use baseUrl for HTTP requests
 *     }
 * }
 * </pre>
 */
@Slf4j
public class ContainerFixture implements BeforeAllCallback, AfterAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(ContainerFixture.class);
    private static final String CONTAINER_KEY = "container";
    private static final String BASE_URL_KEY = "baseUrl";
    private static final String ACTIVE_CLASSES_COUNT_KEY = "activeClassesCount";

    // Static singleton for easy access (set during beforeAll)
    private static volatile SdmxProxyContainer staticContainer;
    private static volatile String staticBaseUrl;

    /**
     * Get the base URL of the running container.
     * This method can be called from any test method after the container has started.
     *
     * @return Base URL (e.g., "http://localhost:32891")
     * @throws IllegalStateException if container has not been started
     */
    public static String getBaseUrl() {
        if (staticBaseUrl == null) {
            throw new IllegalStateException(
                    "Container has not been started. Ensure @ExtendWith(ContainerFixture.class) is present on the test class."
            );
        }
        return staticBaseUrl;
    }

    /**
     * Get the container instance.
     * Useful for accessing container logs or other container-specific functionality.
     *
     * @return SdmxProxyContainer instance
     * @throws IllegalStateException if container has not been started
     */
    public static SdmxProxyContainer getContainer() {
        if (staticContainer == null) {
            throw new IllegalStateException(
                    "Container has not been started. Ensure @ExtendWith(ContainerFixture.class) is present on the test class."
            );
        }
        return staticContainer;
    }

    private static Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Store store = getStore(context);

        // Increment active classes counter
        Integer activeCount = store.get(ACTIVE_CLASSES_COUNT_KEY, Integer.class);
        if (activeCount == null) {
            activeCount = 0;
        }
        activeCount++;
        store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
        log.debug("Active test classes count: {}", activeCount);

        // Check if container is already started (for parallel test execution)
        SdmxProxyContainer container = store.get(CONTAINER_KEY, SdmxProxyContainer.class);
        if (container == null) {
            // Container doesn't exist - create and start it
            log.info("Starting sdmx-proxy container for E2E tests...");
            container = new SdmxProxyContainer();

            container.withEnv("SDMXPROXY_REGISTRY_CONFIG_SOURCE_FILENAME", "/opt/epam/sdmx-proxy/sdmx_registries_config.json");
            container.withEnv("SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE", "FILESYSTEM");
            container.withEnv("FEIGN_LOG_LEVEL", "HEADERS");
            container.withEnv("JAVA_OPTS", "-server -Djava.awt.headless=true -XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=50.0 -XX:+AlwaysActAsServerClassMachine -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -XX:+ExitOnOutOfMemoryError -Dnetworkaddress.cache.ttl=60s");
            container.start();

            store.put(CONTAINER_KEY, container);
            String baseUrl = container.getBaseUrl();
            store.put(BASE_URL_KEY, baseUrl);

            // Set static references for easy access
            staticContainer = container;
            staticBaseUrl = baseUrl;

            log.info("Container started successfully. Base URL: {}", baseUrl);
        } else if (!container.isRunning()) {
            // Container exists but is not running - restart it (shouldn't happen normally)
            log.warn("Container was stopped unexpectedly. Restarting container...");
            container.start();

            // Update base URL in case port changed
            String baseUrl = container.getBaseUrl();
            store.put(BASE_URL_KEY, baseUrl);

            // Update static references
            staticContainer = container;
            staticBaseUrl = baseUrl;

            log.info("Container restarted successfully. Base URL: {}", baseUrl);
        } else {
            // Container exists and is running - reuse it
            log.debug("Container already running. Reusing existing container.");
            // Update static references
            staticContainer = container;
            staticBaseUrl = store.get(BASE_URL_KEY, String.class);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Store store = getStore(context);

        // Decrement active classes counter
        Integer activeCount = store.get(ACTIVE_CLASSES_COUNT_KEY, Integer.class);
        if (activeCount != null && activeCount > 0) {
            activeCount--;
            store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
            log.debug("Active test classes count: {}", activeCount);
        } else {
            // This shouldn't happen, but handle it gracefully
            log.warn("Active classes count was null or already 0. Setting to 0.");
            activeCount = 0;
            store.put(ACTIVE_CLASSES_COUNT_KEY, activeCount);
        }

        // Only stop container when all test classes are done
        if (activeCount == 0) {
            SdmxProxyContainer container = store.get(CONTAINER_KEY, SdmxProxyContainer.class);

            if (container != null && container.isRunning()) {
                log.info("All test classes completed. Stopping sdmx-proxy container...");
                container.stop();
                log.info("Container stopped.");

                // Remove container from store to allow fresh start if needed
                store.remove(CONTAINER_KEY);
                store.remove(BASE_URL_KEY);
            }

            // Clear static references only when all classes are done
            staticContainer = null;
            staticBaseUrl = null;
        } else {
            log.debug("Other test classes still active (count: {}). Keeping container running.", activeCount);
            // Don't clear static references - other test classes still need them
        }
    }
}
