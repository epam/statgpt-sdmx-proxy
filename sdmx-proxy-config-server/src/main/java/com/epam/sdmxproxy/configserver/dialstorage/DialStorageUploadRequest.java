package com.epam.sdmxproxy.configserver.dialstorage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DialStorageUploadRequest {

    private byte[] content;
}
