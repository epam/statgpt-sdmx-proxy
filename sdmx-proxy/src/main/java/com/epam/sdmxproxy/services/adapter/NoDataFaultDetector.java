package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * Recognises a registry error that means "no observations" rather than "bad request".
 * <p>
 * Eurostat answers a {@code TIME_PERIOD} filter that matches no period of the dataflow with
 * {@code 400}, fault code {@code 150} and {@code INVALID_QUERY_DIMENSION_VALUE ... TIME_PERIOD: ...}.
 * A malformed time filter uses fault code {@code 140} ({@code TIME_PERIOD_FILTER_SPEC_INVALID}) and
 * stays an error. The match is established empirically against Eurostat and is enabled per
 * registry through {@link DataEndpointConfiguration#isEmptyOnTimePeriodRejection()}.
 */
@Component
public class NoDataFaultDetector {

    private static final int BAD_REQUEST = 400;
    private static final String DIMENSION_VALUE_FAULT = "INVALID_QUERY_DIMENSION_VALUE";
    private static final String TIME_PERIOD = "TIME_PERIOD";

    public boolean isNoData(FeignException exception, DataEndpointConfiguration dataConfig) {
        if (dataConfig == null || !dataConfig.isEmptyOnTimePeriodRejection() || exception.status() != BAD_REQUEST) {
            return false;
        }
        String body = exception.contentUTF8();
        return body != null && body.contains(DIMENSION_VALUE_FAULT) && body.contains(TIME_PERIOD);
    }
}
