package com.epam.sdmxproxy.e2e.support.container;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Configuration class that reads Docker image tag and registry from system properties or environment variables.
 * Environment variables take precedence over system properties.
 * Also handles Docker registry authentication if credentials are provided.
 */
@Slf4j
public class DockerImageConfig {

    private static final String DEFAULT_IMAGE_NAME = "statgpt/statgpt-sdmx-proxy";
    private static final String DEFAULT_TAG = "latest";

    private static final String SYSTEM_PROP_TAG = "docker.image.tag";
    private static final String SYSTEM_PROP_REGISTRY = "docker.image.registry";
    private static final String ENV_TAG = "DOCKER_IMAGE_TAG";
    private static final String ENV_REGISTRY = "DOCKER_IMAGE_REGISTRY";

    // Docker login credentials
    private static final String ENV_DOCKER_USER = "E2E_DOCKER_USER";
    private static final String ENV_DOCKER_PASS = "E2E_DOCKER_PASS";

    private final String imageName;
    private final String tag;
    private final String registry;
    private final String fullImageName;

    public DockerImageConfig() {

        this.tag = readTag();
        this.registry = readRegistry();
        this.imageName = DEFAULT_IMAGE_NAME;
        this.fullImageName = buildFullImageName();

        // Perform Docker login if credentials are available
        performDockerLoginIfNeeded();
    }

    private String readTag() {
        // Environment variable takes precedence
        String envTag = System.getenv(ENV_TAG);
        if (envTag != null && !envTag.isEmpty()) {
            return envTag;
        }

        // Fall back to system property
        String sysTag = System.getProperty(SYSTEM_PROP_TAG);
        return sysTag != null && !sysTag.isEmpty() ? sysTag : DEFAULT_TAG;
    }

    private String readRegistry() {
        // Environment variable takes precedence
        String envRegistry = System.getenv(ENV_REGISTRY);
        if (envRegistry != null && !envRegistry.isEmpty()) {
            return envRegistry;
        }

        // Fall back to system property
        String sysRegistry = System.getProperty(SYSTEM_PROP_REGISTRY);
        return sysRegistry != null && !sysRegistry.isEmpty() ? sysRegistry : "";
    }

    private String buildFullImageName() {
        if (tag.equals("local")) {
            return String.format("%s:%s", imageName, tag);
        }
        if (registry != null && !registry.isEmpty()) {
            // Remove protocol (http:// or https://) if present
            String cleanRegistry = registry.replaceFirst("^https?://", "");
            // Remove trailing slash if present
            cleanRegistry = cleanRegistry.endsWith("/")
                    ? cleanRegistry.substring(0, cleanRegistry.length() - 1)
                    : cleanRegistry;
            return String.format("%s/%s:%s", cleanRegistry, imageName, tag);
        }

        return String.format("%s:%s", imageName, tag);
    }

    // Getters
    public String getImageName() {
        return imageName;
    }

    public String getTag() {
        return tag;
    }

    public String getRegistry() {
        return registry;
    }

    public String getFullImageName() {
        return fullImageName;
    }

    /**
     * Performs Docker login if credentials are available in environment variables.
     * This is needed when pulling images from private registries like DOCKER.
     */
    private void performDockerLoginIfNeeded() {
        String username = System.getenv(ENV_DOCKER_USER);
        String password = System.getenv(ENV_DOCKER_PASS);

        // Only attempt login if all credentials are present
        if (username != null && !username.isEmpty() &&
                password != null && !password.isEmpty() &&
                registry != null && !registry.isEmpty()) {

            log.info("Docker login credentials detected. Attempting to login to registry: {}", registry);
            try {
                performDockerLogin(username, password, registry);
                log.info("Docker login successful");
            } catch (Exception e) {
                log.warn("Docker login failed. This may cause issues if pulling from a private registry: {}", e.getMessage());
                log.debug("Docker login error details", e);
            }
        } else {
            log.debug("Docker login credentials not found. Skipping login (assuming public registry or already authenticated)");
        }
    }

    /**
     * Executes docker login command to authenticate with the registry.
     *
     * @param username Docker registry username
     * @param password Docker registry password
     * @param registry Docker registry URL
     * @throws IOException          if the docker command fails
     * @throws InterruptedException if the process is interrupted
     */
    private void performDockerLogin(String username, String password, String registry)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "docker", "login",
                "--username", username,
                "--password", password,
                registry
        );

        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try (BufferedReader inputReader = process.inputReader()) {
            log.info(inputReader.readAllAsString());
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Docker login command timed out after 30 seconds");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Docker login failed with exit code: " + exitCode);
        }
    }

    @Override
    public String toString() {
        return String.format("DockerImageConfig{image='%s', tag='%s', registry='%s'}",
                imageName, tag, registry.isEmpty() ? "(none)" : registry);
    }
}
