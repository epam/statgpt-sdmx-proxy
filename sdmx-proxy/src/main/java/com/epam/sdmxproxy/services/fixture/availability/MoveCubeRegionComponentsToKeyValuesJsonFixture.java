package com.epam.sdmxproxy.services.fixture.availability;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON fixture that moves dimension entries from {@code components} to {@code keyValues}
 * in SDMX-JSON 2.0.0 availability responses (dataConstraints.cubeRegions).
 * <p>
 * Per SDMX-JSON 2.0.0, dimensions must be in {@code keyValues}, not {@code components}.
 * Some registries (e.g., IMF) incorrectly place all entries in {@code components}.
 * This fixture uses the DSD from {@link SdmxBeans} to determine which component IDs
 * are dimensions and moves them to {@code keyValues}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MoveCubeRegionComponentsToKeyValuesJsonFixture implements AvailabilityFixture {

    public static final String KEY_VALUES = "keyValues";
    public static final String COMPONENTS = "components";
    private final ObjectMapper objectMapper;

    @Override
    public AvailabilityFixtureType getType() {
        return AvailabilityFixtureType.MOVE_CUBE_REGION_COMPONENTS_TO_KEY_VALUES;
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    @Override
    @SneakyThrows
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        if (sdmxBeans == null || sdmxBeans.getDataStructures() == null || sdmxBeans.getDataStructures().isEmpty()) {
            log.warn("No DSD in SdmxBeans, skipping MoveCubeRegionComponentsToKeyValues fixture");
            return input;
        }

        Set<String> dimensionIds = extractDimensionIds(sdmxBeans);
        if (dimensionIds.isEmpty()) {
            log.warn("No dimensions found in DSD, skipping MoveCubeRegionComponentsToKeyValues fixture");
            return input;
        }

        JsonNode root = objectMapper.readTree(input);

        moveComponentsToKeyValues(root, dimensionIds);

        return new ByteArrayInputStream(objectMapper.writeValueAsBytes(root));
    }

    private Set<String> extractDimensionIds(SdmxBeans sdmxBeans) {
        DataStructureBean dsd = sdmxBeans.getDataStructures().iterator().next();
        return dsd.getDimensions().stream()
                .map(DimensionBean::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void moveComponentsToKeyValues(JsonNode root, Set<String> dimensionIds) {
        JsonNode dataConstraints = root.path("data").path("dataConstraints");
        if (dataConstraints.isMissingNode() || !dataConstraints.isArray()) {
            return;
        }

        int totalMoved = 0;
        for (JsonNode dataConstraint : dataConstraints) {
            JsonNode cubeRegions = dataConstraint.path("cubeRegions");
            if (cubeRegions.isMissingNode() || !cubeRegions.isArray()) {
                continue;
            }

            for (JsonNode cubeRegion : cubeRegions) {
                if (!cubeRegion.isObject()) {
                    continue;
                }
                totalMoved += processCubeRegion((ObjectNode) cubeRegion, dimensionIds);
            }
        }

        log.debug("Moved {} dimension entries from components to keyValues", totalMoved);
    }

    private int processCubeRegion(ObjectNode cubeRegion, Set<String> dimensionIds) {
        JsonNode componentsNode = cubeRegion.path(COMPONENTS);
        if (componentsNode.isMissingNode() || !componentsNode.isArray()) {
            return 0;
        }

        ArrayNode components = (ArrayNode) componentsNode;
        ArrayNode keyValues;
        if (cubeRegion.has(KEY_VALUES) && cubeRegion.get(KEY_VALUES).isArray()) {
            keyValues = (ArrayNode) cubeRegion.get(KEY_VALUES);
        } else {
            keyValues = cubeRegion.putArray(KEY_VALUES);
        }

        ArrayNode remainingComponents = objectMapper.createArrayNode();
        int moved = 0;

        List<JsonNode> toProcess = new ArrayList<>();
        components.forEach(toProcess::add);
        for (JsonNode component : toProcess) {
            JsonNode idNode = component.path("id");
            if (idNode.isMissingNode() || !idNode.isTextual()) {
                remainingComponents.add(component);
                continue;
            }
            String id = idNode.asText();
            if (dimensionIds.contains(id)) {
                keyValues.add(component);
                moved++;
            } else {
                remainingComponents.add(component);
            }
        }

        cubeRegion.set(COMPONENTS, remainingComponents);
        return moved;
    }
}
