package com.epam.sdmxproxy.common.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataComponentFilterDto {
    private String componentCode;
    private String value;
    private DataComponentFilterOperator operator;
}
