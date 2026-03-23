package com.epam.sdmxproxy.registry.api.config;

import feign.FeignException;
import feign.Response;
import feign.codec.Decoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Type;

@Component
public class InputStreamFeignDecoder implements Decoder {
    @Override
    public Object decode(Response response, Type type) throws IOException, FeignException {
        return response.body().asInputStream();
    }
}
