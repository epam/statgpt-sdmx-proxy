package com.epam.sdmxproxy.e2e.support.util;

import com.epam.sdmxproxy.e2e.support.url.ApiKeyProvider;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Wrapper around RestAssured for making HTTP requests to the containerized application.
 * Provides convenience methods for common HTTP operations.
 * <p>
 * If {@link ApiKeyProvider#getApiKey()} returns a non-null value (i.e. {@code E2E_PASSWORD}
 * is set), every request built by this client carries an {@code Api-Key} header so suites
 * pass through a DIAL-fronted proxy. When unset, no auth header is added.
 */
@Slf4j
public class RestClient {

    private static final String API_KEY_HEADER = "Api-Key";

    private final String baseUrl;

    public RestClient(String baseUrl) {
        this.baseUrl = baseUrl;
        // Configure RestAssured to use the base URL
        RestAssured.baseURI = baseUrl;
    }

    private static RequestSpecification withApiKey(RequestSpecification spec) {
        String apiKey = ApiKeyProvider.getApiKey();
        if (apiKey != null) {
            spec.header(API_KEY_HEADER, apiKey);
        }
        return spec;
    }

    /**
     * Create a GET request specification.
     *
     * @param path API endpoint path (e.g., "/sdmx/proxy/api/v0/health")
     * @return RequestSpecification for chaining
     */
    public RequestSpecification get(String path) {
        log.debug("GET {}{}", baseUrl, path);
        return withApiKey(RestAssured.given().basePath(path));
    }

    /**
     * Execute a GET request and return the response.
     *
     * @param path API endpoint path
     * @return Response object
     */
    public Response getResponse(String path) {
        return get(path).when().get();
    }

    /**
     * Execute a GET request with query parameters.
     *
     * @param path        API endpoint path
     * @param queryParams Query parameters map
     * @return Response object
     */
    public Response getResponse(String path, Map<String, Object> queryParams) {
        return get(path)
                .queryParams(queryParams)
                .when()
                .get();
    }

    /**
     * Execute a GET request with headers.
     *
     * @param path    API endpoint path
     * @param headers Headers map
     * @return Response object
     */
    public Response getResponseWithHeaders(String path, Map<String, String> headers) {
        return get(path)
                .headers(headers)
                .when()
                .get();
    }

    /**
     * Execute a GET request with Accept header.
     *
     * @param path         API endpoint path
     * @param acceptHeader Accept header value (e.g., "application/json")
     * @return Response object
     */
    public Response getResponseWithAccept(String path, String acceptHeader) {
        return get(path)
                .accept(acceptHeader)
                .when()
                .get();
    }

    /**
     * Execute a GET request with Accept header and query parameters.
     *
     * @param path         API endpoint path
     * @param acceptHeader Accept header value (e.g., "application/json")
     * @param queryParams  Query parameters map
     * @return Response object
     */
    public Response getResponseWithAccept(String path, String acceptHeader, Map<String, Object> queryParams) {
        return get(path)
                .accept(acceptHeader)
                .queryParams(queryParams)
                .when()
                .get();
    }

    /**
     * Create a POST request specification.
     *
     * @param path API endpoint path (e.g., "/sdmx/proxy/api/v0/endpoint")
     * @return RequestSpecification for chaining
     */
    public RequestSpecification post(String path) {
        log.debug("POST {}{}", baseUrl, path);
        return withApiKey(RestAssured.given().basePath(path));
    }

    /**
     * Execute a POST request with a request body and return the response.
     *
     * @param path API endpoint path
     * @param body Request body object (will be serialized to JSON by default)
     * @return Response object
     */
    public Response postResponse(String path, Object body) {
        return post(path)
                .body(body)
                .contentType(ContentType.JSON)
                .when()
                .post();
    }

    /**
     * Execute a POST request with a request body and Content-Type header.
     *
     * @param path        API endpoint path
     * @param body        Request body object
     * @param contentType Content-Type header value (e.g., "application/json", "application/xml")
     * @return Response object
     */
    public Response postResponse(String path, Object body, String contentType) {
        return post(path)
                .contentType(contentType)
                .body(body)
                .when()
                .post();
    }

    /**
     * Execute a POST request with a request body and headers.
     *
     * @param path    API endpoint path
     * @param body    Request body object
     * @param headers Headers map
     * @return Response object
     */
    public Response postResponseWithHeaders(String path, Object body, Map<String, String> headers) {
        return post(path)
                .headers(headers)
                .body(body)
                .when()
                .post();
    }

    /**
     * Execute a POST request with a string request body.
     *
     * @param path        API endpoint path
     * @param body        Request body as string
     * @param contentType Content-Type header value (e.g., "application/json", "application/xml")
     * @return Response object
     */
    public Response postResponse(String path, String body, String contentType) {
        return post(path)
                .contentType(contentType)
                .body(body)
                .when()
                .post();
    }

    /**
     * Get the base URL.
     *
     * @return Base URL string
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Create a request specification builder for custom requests.
     *
     * @return RequestSpecification builder
     */
    public RequestSpecification given() {
        return withApiKey(RestAssured.given());
    }
}
