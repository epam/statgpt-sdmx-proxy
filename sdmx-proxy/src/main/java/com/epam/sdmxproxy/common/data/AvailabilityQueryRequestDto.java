package com.epam.sdmxproxy.common.data;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityQueryRequestDto {
    private String key;
    private String componentId;
    private Instant updatedAfter;
    private String mode;
    private String references;
    private String reportingYearStartDay;

    @JsonAlias("c")  // standard name is `c`
    private List<DataComponentFilterDto> filter = new ArrayList<>();
}
