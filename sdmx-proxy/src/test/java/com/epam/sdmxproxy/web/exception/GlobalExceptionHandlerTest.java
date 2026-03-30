package com.epam.sdmxproxy.web.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler sut = new GlobalExceptionHandler(new ObjectMapper());

    private static FeignException createFeignException(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "https://example.com/test", Collections.emptyMap(), null, new RequestTemplate());
        byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return FeignException.errorStatus("TestClient#method", feign.Response.builder()
                .status(status)
                .reason("reason")
                .request(request)
                .headers(Collections.emptyMap())
                .body(bodyBytes)
                .build());
    }

    private String extractMessage(FeignException ex) {
        ResponseEntity<ErrorResponse> response = sut.handleFeignException(ex);
        assertNotNull(response.getBody());
        return response.getBody().getMessage();
    }

    @Test
    void handleFeignException_sdmxJsonErrorFormat() {
        String body = "{\"errors\":[{\"code\":400,\"message\":\"Illegal version reference 'latest'\"}]}";
        FeignException ex = createFeignException(400, body);

        String result = extractMessage(ex);

        assertEquals("Illegal version reference 'latest'", result);
    }

    @Test
    void handleFeignException_simpleMessageField() {
        String body = "{\"message\":\"Not Found\",\"status\":404}";
        FeignException ex = createFeignException(404, body);

        String result = extractMessage(ex);

        assertEquals("Not Found", result);
    }

    @Test
    void handleFeignException_nonJsonBody() {
        String body = "Something went wrong on the server";
        FeignException ex = createFeignException(500, body);

        String result = extractMessage(ex);

        assertEquals("Something went wrong on the server", result);
    }

    @Test
    void handleFeignException_emptyBody_fallsBackToReasonPhrase() {
        FeignException ex = createFeignException(400, "");

        String result = extractMessage(ex);

        assertEquals("Bad Request", result);
    }

    @Test
    void handleFeignException_nullBody_fallsBackToReasonPhrase() {
        FeignException ex = createFeignException(404, null);

        String result = extractMessage(ex);

        assertEquals("Not Found", result);
    }

    @Test
    void handleFeignException_longBody_notTruncated() {
        String body = "x".repeat(600);
        FeignException ex = createFeignException(500, body);

        String result = extractMessage(ex);

        assertEquals(body, result);
    }

    @Test
    void handleFeignException_jsonWithoutExpectedFields() {
        String body = "{\"error\":\"some_code\",\"detail\":\"some detail\"}";
        FeignException ex = createFeignException(400, body);

        String result = extractMessage(ex);

        assertEquals(body, result);
    }

    @Test
    void handleFeignException_multipleErrors_usesFirst() {
        String body = "{\"errors\":[{\"code\":400,\"message\":\"First error\"},{\"code\":400,\"message\":\"Second error\"}]}";
        FeignException ex = createFeignException(400, body);

        String result = extractMessage(ex);

        assertEquals("First error", result);
    }

    @Test
    void handleFeignException_preservesStatusCode() {
        FeignException ex = createFeignException(404, "{\"message\":\"Not Found\"}");

        ResponseEntity<ErrorResponse> response = sut.handleFeignException(ex);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, response.getBody().getStatus());
    }
}
