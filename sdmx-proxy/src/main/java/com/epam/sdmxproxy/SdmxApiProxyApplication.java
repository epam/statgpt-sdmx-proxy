package com.epam.sdmxproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@SpringBootApplication
@EnableWebSecurity
public class SdmxApiProxyApplication {

    static void main(String[] args) {
        SpringApplication.run(SdmxApiProxyApplication.class, args);
    }

}
