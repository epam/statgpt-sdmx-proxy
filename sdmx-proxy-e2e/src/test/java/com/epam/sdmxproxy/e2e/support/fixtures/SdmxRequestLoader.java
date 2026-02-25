package com.epam.sdmxproxy.e2e.support.fixtures;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads SDMX request/response fixture files from test resources.
 * Handles UTF-8 encoding and provides convenience methods for loading test fixtures.
 */
@Slf4j
public class SdmxRequestLoader {

    private static final String FIXTURES_BASE_PATH = "fixtures/";

    /**
     * Loads a request fixture file from test resources.
     *
     * @param relativePath Relative path from fixtures directory (e.g., "requests/dataquery_happy_path_sdmx30.xml")
     * @return Content of the file as a string (UTF-8)
     * @throws IOException if the file cannot be read
     */
    public String loadRequest(String relativePath) throws IOException {
        return loadResource(FIXTURES_BASE_PATH + "requests/" + relativePath);
    }

    /**
     * Loads a response fixture file from test resources.
     *
     * @param relativePath Relative path from fixtures directory (e.g., "responses/dataquery_happy_path_sdmx30_expected.json")
     * @return Content of the file as a string (UTF-8)
     * @throws IOException if the file cannot be read
     */
    public String loadResponse(String relativePath) throws IOException {
        return loadResource(FIXTURES_BASE_PATH + "responses/" + relativePath);
    }

    /**
     * Loads a resource file from test resources.
     *
     * @param resourcePath Full path from test resources root (e.g., "fixtures/requests/dataquery_happy_path_sdmx30.xml")
     * @return Content of the file as a string (UTF-8)
     * @throws IOException if the file cannot be read
     */
    public String loadResource(String resourcePath) throws IOException {
        log.debug("Loading resource: {}", resourcePath);

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(resourcePath);

        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }

        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads a schema file from test resources.
     *
     * @param relativePath Relative path from fixtures/schemas directory (e.g., "dataquery_v3.0_schema.json")
     * @return Content of the file as a string (UTF-8)
     * @throws IOException if the file cannot be read
     */
    public String loadSchema(String relativePath) throws IOException {
        return loadResource(FIXTURES_BASE_PATH + "schemas/" + relativePath);
    }
}
