package com.epam.sdmxproxy.configserver.controller;

import com.epam.sdmxproxy.configserver.api.HealthCheckApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class HealthCheckController implements HealthCheckApi {

    @Override
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
