package com.epam.sdmxproxy.configserver.dialstorage;

public interface DialStorageClient {

    String getBucketName();

    byte[] getObjectContent(String bucketName, String objectPath);

    void putObjectContent(String bucketName, String objectPath, byte[] content);
}
