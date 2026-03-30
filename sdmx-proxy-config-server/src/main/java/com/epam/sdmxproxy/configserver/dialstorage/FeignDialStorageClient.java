package com.epam.sdmxproxy.configserver.dialstorage;

import com.epam.sdmxproxy.configserver.config.ConfigSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "sdmxproxy.configserver.source.type", havingValue = ConfigSourceType.Values.DIAL_STORAGE)
@RequiredArgsConstructor
public class FeignDialStorageClient implements DialStorageClient {

    private final DialStorageFeignApi dialStorageFeignApi;
    private final DialStorageSourceProperties properties;
    private final ObjectMapper objectMapper;

    private static String encodeFilePath(String objectPath) {
        if (objectPath == null || objectPath.isEmpty()) {
            return objectPath;
        }
        return Arrays.stream(objectPath.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    @Override
    public String getBucketName() {
        try {
            String bucket = dialStorageFeignApi.getBucket(properties.getApiKey());
            JsonNode jsonNode = objectMapper.readTree(bucket);
            return jsonNode.get("bucket").textValue();
        } catch (Exception e) {
            log.error("Dial Storage get bucket failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to get Dial Storage bucket: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] getObjectContent(String bucketName, String objectPath) {
        try {
            String encodedPath = encodeFilePath(objectPath);
            return dialStorageFeignApi.getFile(properties.getApiKey(), bucketName, encodedPath);
        } catch (Exception e) {
            log.error("Dial Storage get object failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to get Dial Storage object: " + e.getMessage(), e);
        }
    }

    @Override
    public void putObjectContent(String bucketName, String objectPath, byte[] content) {
        try {
            String encodedPath = encodeFilePath(objectPath);
            dialStorageFeignApi.uploadFile(properties.getApiKey(), bucketName, encodedPath, new DialStorageUploadRequest(content));
            log.debug("Successfully wrote object to Dial Storage: {}/{}", bucketName, objectPath);
        } catch (Exception e) {
            log.error("Dial Storage put object failed: {}", e.getMessage());
            throw new IllegalStateException("Failed to put Dial Storage object: " + e.getMessage(), e);
        }
    }
}
