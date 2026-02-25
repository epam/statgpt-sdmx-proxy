package com.epam.sdmxproxy.e2e.support.container;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Container wrapper for sdmx-proxy Docker image.
 * Extends GenericContainer and configures readiness wait strategy using health endpoint.
 */
@Slf4j
public class SdmxProxyContainer extends GenericContainer<SdmxProxyContainer> {

    private static final int APP_PORT = 8050;
    private static final String HEALTH_ENDPOINT = "/statgpt/sdmx-proxy/api/v0/health";
    private static final String HEALTH_RESPONSE = "OK";

    private String baseUrl;

    public SdmxProxyContainer() {
        String imageName = new DockerImageConfig().getFullImageName();

        log.info("Initializing container with image: {}", imageName);

        // Use DockerImageName.parse() for proper handling of registry URLs
        super(DockerImageName.parse(imageName));
        addExposedPort(APP_PORT);

        // Configure readiness wait strategy
        super.setWaitStrategy(
                new HttpWaitStrategy()
                        .forPath(HEALTH_ENDPOINT)
                        .forStatusCode(200)
                        .forResponsePredicate((String responseBody) ->
                                responseBody != null && responseBody.contains(HEALTH_RESPONSE)
                        )
                        .withStartupTimeout(Duration.ofSeconds(getStartupTimeoutSeconds()))
        );
    }

    @Override
    public void start() {
        try {
            super.start();
            // Derive base URL after container is ready
            String host = getHost();
            Integer mappedPort = getMappedPort(APP_PORT);
            this.baseUrl = String.format("http://%s:%d", host, mappedPort);
            log.info("Container started. Base URL: {}", baseUrl);
        } catch (Exception e) {
            // Log container logs if startup fails
            if (isRunning()) {
                log.error("Container startup failed. Container logs:\n{}", getLogs());
            }
            throw e;
        }
    }

    private int getStartupTimeoutSeconds() {
        String timeoutProp = System.getProperty("e2e.startup.timeout.seconds");
        if (timeoutProp != null) {
            try {
                return Integer.parseInt(timeoutProp);
            } catch (NumberFormatException e) {
                log.warn("Invalid e2e.startup.timeout.seconds value: {}. Using default 60 seconds.", timeoutProp);
            }
        }
        return 60; // Default timeout: 60 seconds
    }

    /**
     * Get the base URL for making HTTP requests to the containerized application.
     *
     * @return Base URL (e.g., "http://localhost:32891")
     */
    public String getBaseUrl() {
        if (baseUrl == null) {
            throw new IllegalStateException("Container has not been started yet. Call start() first.");
        }
        return baseUrl;
    }
}
