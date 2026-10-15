package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoDataFaultDetectorTest {

    private static final String NO_DATA_FAULT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><S:Fault xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><faultcode>150</faultcode><faultstring>INVALID_QUERY_DIMENSION_VALUE: Query is invalid as per its structure&apos;s definition. The following values for dimension are not allowed: TIME_PERIOD: &gt;=2023-01-01 and &lt;=2026-12-31.</faultstring></S:Fault>";
    private static final String MALFORMED_TIME_FAULT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><S:Fault xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><faultcode>140</faultcode><faultstring>TIME_PERIOD_FILTER_SPEC_INVALID: Impossible to apply time dimension filtering</faultstring></S:Fault>";
    private static final String DIMENSION_VALUE_FAULT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><S:Fault xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><faultcode>150</faultcode><faultstring>INVALID_QUERY_DIMENSION_VALUE: Query is invalid as per its structure&apos;s definition. The following values for dimension are not allowed: FREQ=ALL.</faultstring></S:Fault>";

    private final NoDataFaultDetector detector = new NoDataFaultDetector();

    @Test
    void timePeriodRejectionWithFlag_isNoData() {
        assertTrue(detector.isNoData(badRequest(NO_DATA_FAULT), config(true)));
    }

    @Test
    void timePeriodRejectionWithoutFlag_isNotNoData() {
        assertFalse(detector.isNoData(badRequest(NO_DATA_FAULT), config(false)));
    }

    @Test
    void timePeriodRejectionWithoutDataConfig_isNotNoData() {
        assertFalse(detector.isNoData(badRequest(NO_DATA_FAULT), null));
    }

    @Test
    void malformedTimeFilter_isNotNoData() {
        assertFalse(detector.isNoData(badRequest(MALFORMED_TIME_FAULT), config(true)));
    }

    @Test
    void rejectedNonTimeDimensionValue_isNotNoData() {
        assertFalse(detector.isNoData(badRequest(DIMENSION_VALUE_FAULT), config(true)));
    }

    @Test
    void timePeriodFaultWithOtherStatus_isNotNoData() {
        FeignException notFound = new FeignException.NotFound("not found", request(), NO_DATA_FAULT.getBytes(StandardCharsets.UTF_8), Map.of());
        assertFalse(detector.isNoData(notFound, config(true)));
    }

    private static FeignException badRequest(String body) {
        return new FeignException.BadRequest("bad request", request(), body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "https://example.org/data", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static DataEndpointConfiguration config(boolean emptyOnTimePeriodRejection) {
        DataEndpointConfiguration config = new DataEndpointConfiguration();
        config.setEmptyOnTimePeriodRejection(emptyOnTimePeriodRejection);
        return config;
    }
}
