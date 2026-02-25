package com.epam.sdmxproxy.registry.api.config;

import feign.Client;
import feign.codec.Encoder;
import feign.okhttp.OkHttpClient;
import lombok.RequiredArgsConstructor;
import okhttp3.ConnectionPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class FeignConfig {

    private final FeignHttpClientProperties feignHttpClientProperties;

    @Bean
    public Encoder feignEncoder() {
        return new Encoder.Default();
    }

    @Bean
    public Client baseOkHttpClient() {
        okhttp3.OkHttpClient.Builder okHttpClientBuilder = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(feignHttpClientProperties.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(feignHttpClientProperties.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(feignHttpClientProperties.getWriteTimeout(), TimeUnit.MILLISECONDS);

        if (feignHttpClientProperties.getConnectionPoolingEnabled()) {
            ConnectionPool connectionPool = new ConnectionPool(
                    feignHttpClientProperties.getMaxIdleConnections(),
                    feignHttpClientProperties.getKeepAliveDuration(),
                    TimeUnit.MILLISECONDS
            );
            okHttpClientBuilder.connectionPool(connectionPool);
        }

        okhttp3.OkHttpClient okHttpClient = okHttpClientBuilder.build();
        return new OkHttpClient(okHttpClient);
    }

}
