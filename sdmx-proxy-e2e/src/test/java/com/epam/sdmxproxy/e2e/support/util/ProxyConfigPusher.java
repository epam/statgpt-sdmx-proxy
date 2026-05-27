package com.epam.sdmxproxy.e2e.support.util;

import io.restassured.response.Response;
import lombok.experimental.UtilityClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POSTs a {@code ProxyConfiguration} JSON to the proxy's {@code /config} endpoint and asserts
 * the proxy accepted it. Fails fast in {@code @BeforeAll} with a clear message if the test
 * endpoint isn't enabled -- otherwise every downstream parametrized case runs against the
 * proxy's classpath default and asserts against the wrong state for the entire suite duration.
 *
 * <p>The proxy must be started with {@code SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true} for
 * this to succeed; see {@code sdmx-proxy-e2e/README.md}.
 */
@UtilityClass
public class ProxyConfigPusher {

    public static void push(RestClient restClient, String configPath, String configJson) {
        Response response = restClient.postResponse(configPath, configJson);
        assertThat(response.getStatusCode())
                .as("POST %s returned HTTP %d (body: %s) -- start the proxy with "
                                + "SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true so E2E setups can push their ProxyConfiguration. "
                                + "See sdmx-proxy-e2e/README.md.",
                        configPath, response.getStatusCode(), response.getBody().asString())
                .isEqualTo(200);
    }
}
