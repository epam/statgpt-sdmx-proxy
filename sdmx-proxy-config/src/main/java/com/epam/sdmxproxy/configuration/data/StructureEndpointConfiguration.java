package com.epam.sdmxproxy.configuration.data;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;


@Data
@EqualsAndHashCode(callSuper = true)
public class StructureEndpointConfiguration extends EndpointConfiguration {

    private Set<String> supportedStructures;

}
