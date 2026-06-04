package com.epam.sdmxproxy.controller.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Enumeration;

@Slf4j
public class ControllerUtils {
    private ControllerUtils() {
    }

    public static void logRequestUrl(String accept, ControllerUtils.ControllerType type) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String queryString = request.getQueryString();
        String fullUrl = request.getRequestURL().toString() + (queryString != null ? "?" + queryString : "");
        log.debug("Incoming get {} request {} accept: {}", type.getName(), fullUrl, accept);
    }

    // TEMPORARY diagnostic (design 034): dumps the full inbound request — method, URL, query string
    // and ALL headers (incl. the Authorization bearer token, API-KEY, X-JOB-TITLE, X-CONVERSATION-ID,
    // traceparent) — so we can see exactly what DIAL forwards on the review env. Logs secrets in
    // plaintext on purpose; REMOVE once the identity contract is confirmed.
    public static void logFullIncomingRequest(ControllerUtils.ControllerType type) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String queryString = request.getQueryString();
        String fullUrl = request.getRequestURL().toString() + (queryString != null ? "?" + queryString : "");
        StringBuilder headers = new StringBuilder();
        for (Enumeration<String> names = request.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            for (Enumeration<String> values = request.getHeaders(name); values.hasMoreElements(); ) {
                headers.append("\n  ").append(name).append(": ").append(values.nextElement());
            }
        }
        log.info("[DIAL-REQUEST-DIAG] {} request method={} url={} headers:{}", type.getName(), request.getMethod(), fullUrl, headers);
    }

    public enum ControllerType {
        STRUCTURE("structure"),
        AVAILABILITY("availability"),
        DATA("data");

        private String name;

        ControllerType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
