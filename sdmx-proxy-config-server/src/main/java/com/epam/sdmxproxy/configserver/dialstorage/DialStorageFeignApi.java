package com.epam.sdmxproxy.configserver.dialstorage;

import feign.Headers;
import feign.Param;
import feign.RequestLine;

public interface DialStorageFeignApi {

    @RequestLine("GET /v1/bucket")
    @Headers("Api-Key: {apiKey}")
    String getBucket(@Param("apiKey") String apiKey);

    @RequestLine("GET /v1/files/{bucket}/{path}")
    @Headers("Api-Key: {apiKey}")
    byte[] getFile(@Param("apiKey") String apiKey, @Param("bucket") String bucket, @Param("path") String path);

    @RequestLine("PUT /v1/files/{bucket}/{path}")
    @Headers("Api-Key: {apiKey}")
    void uploadFile(@Param("apiKey") String apiKey, @Param("bucket") String bucket, @Param("path") String path, DialStorageUploadRequest body);
}
