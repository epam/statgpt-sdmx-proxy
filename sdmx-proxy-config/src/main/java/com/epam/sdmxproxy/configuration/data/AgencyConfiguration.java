package com.epam.sdmxproxy.configuration.data;

import lombok.Data;

@Data
public class AgencyConfiguration {

    private String name;

    private String primaryRegistry;

    private boolean allowSubAgencies;

}
