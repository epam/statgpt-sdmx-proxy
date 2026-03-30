package com.epam.sdmxproxy.configserver.dialstorage;

import feign.RequestTemplate;
import feign.codec.Encoder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

@Slf4j
public class DialStorageMultipartEncoder implements Encoder {

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) {
        if (!(object instanceof DialStorageUploadRequest request)) {
            throw new UnsupportedOperationException("DialStorageMultipartEncoder supports only DialStorageUploadRequest, got: " + (object == null ? "null" : object.getClass().getName()));
        }
        RequestBody filePart = RequestBody.create(request.getContent(), MediaType.parse("application/octet-stream"));
        MultipartBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "", filePart)
                .build();

        okio.Buffer buffer = new okio.Buffer();
        try {
            multipartBody.writeTo(buffer);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build multipart body", e);
        }
        byte[] bytes = buffer.readByteArray();
        String contentType = multipartBody.contentType() != null ? multipartBody.contentType().toString() : "multipart/form-data";

        template.body(bytes, StandardCharsets.UTF_8);
        template.header("Content-Type", contentType);
    }
}
