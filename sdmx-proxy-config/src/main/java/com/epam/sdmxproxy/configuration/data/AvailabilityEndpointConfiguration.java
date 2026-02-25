package com.epam.sdmxproxy.configuration.data;

import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(callSuper = true)
public class AvailabilityEndpointConfiguration extends EndpointConfiguration {

    private boolean availabilityEnabled;

}
