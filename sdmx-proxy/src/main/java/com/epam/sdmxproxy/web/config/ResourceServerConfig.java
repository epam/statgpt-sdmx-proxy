package com.epam.sdmxproxy.web.config;

import com.epam.sdmxproxy.configuration.telemetry.CorrelationIdInterceptor;
import com.epam.sdmxproxy.web.config.settings.WebMvcSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class ResourceServerConfig implements WebMvcConfigurer {

    private final WebMvcSettings webMvcSettings;

    public ResourceServerConfig(
            WebMvcSettings webMvcSettings
    ) {
        this.webMvcSettings = webMvcSettings;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SecurityFilterChain chain = http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
        // Workaround for spring-projects/spring-security#15510: the default HeaderWriterFilter races
        // with StreamingResponseBody on cache-hit-fast-path responses, occasionally corrupting
        // Tomcat's MimeHeaders byte buffer so the client reads a malformed header line. Writing the
        // security headers before the controller dispatches avoids the race. The 7.0.5 DSL does not
        // expose this flag, so we flip it on the filter instance after the chain is built.
        chain.getFilters().stream()
                .filter(HeaderWriterFilter.class::isInstance)
                .map(HeaderWriterFilter.class::cast)
                .forEach(filter -> filter.setShouldWriteHeadersEagerly(true));
        return chain;
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Authentication is not supported");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public CorrelationIdInterceptor correlationIdInterceptor() {
        return new CorrelationIdInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(correlationIdInterceptor())
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

}
