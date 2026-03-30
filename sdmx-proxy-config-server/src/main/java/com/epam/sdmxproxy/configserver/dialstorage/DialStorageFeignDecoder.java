package com.epam.sdmxproxy.configserver.dialstorage;

import feign.FeignException;
import feign.Response;
import feign.codec.Decoder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public class DialStorageFeignDecoder implements Decoder {

    @Override
    public Object decode(Response response, Type type) throws IOException, FeignException {
        if (type == Void.class || Void.TYPE.equals(type)) {
            return null;
        }
        if (response.body() == null) {
            return null;
        }
        if (type == String.class) {
            try (InputStream is = response.body().asInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (type == byte[].class) {
            try (InputStream is = response.body().asInputStream()) {
                return is.readAllBytes();
            }
        }
        throw new UnsupportedOperationException("DialStorageFeignDecoder supports only String, byte[], and void, got: " + type);
    }
}
