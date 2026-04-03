package com.epam.sdmxproxy.controller.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
