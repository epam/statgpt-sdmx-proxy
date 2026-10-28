package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.exception.StructureListNotSupportedException;
import com.epam.sdmxproxy.exception.StructureNotFoundException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emulates the SDMX 2.1 structure resources for Statistics Canada -- see design 043, {@code B1}
 * and {@code B2}.
 * <p>
 * StatCan serves only {@code GET structure/Data_Structure_{table}}: a bundle with the agency
 * scheme, the dataflow, the DSD, its codelists, and its concept scheme. The dataflow catalog comes
 * from WDS {@code getAllCubesListLite}. The source answers every structure query with an SDMX-ML
 * 2.1 document holding exactly the requested artefacts, so the router, the caches, the fixtures,
 * and the converters work as they do for any other registry.
 * <p>
 * A bundle is renamed (see {@link StatCanBundleRenamer}) and passed through the configured
 * structure fixtures as a whole before any artefact is selected: copying a representation onto a
 * concept needs the DSD, which a concept-scheme request would otherwise cut out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatCanStructureSource {

    private static final String BUNDLE_CACHE_KEY = "statcan:bundle:%s:%s";

    private static final String CATALOG_CACHE_KEY = "statcan:catalog:%s:%s";

    /** {@code adapterConfig} key: the WDS base URL, {@code .../t1/wds/rest/}. Required. */
    public static final String CATALOG_URL = "catalogUrl";

    /** {@code adapterConfig} key: {@code "true"} lists archived tables in the catalog. */
    public static final String INCLUDE_ARCHIVED = "includeArchived";

    private static final Set<String> WILDCARDS = Set.of("*", "all");

    private static final Set<String> ACCEPTED_VERSIONS = Set.of(StatCanIdentifiers.VERSION, "latest", "+", "~", "*", "all");

    private static final Set<String> STRUCTURE_TYPES = Set.of("dataflow", "datastructure", "codelist", "conceptscheme");

    private final SdmxApiClientProvider sdmxApiClientProvider;
    private final StatCanIdentifiers identifiers;
    private final StatCanBundleRenamer renamer;
    private final StatCanCatalogWriter catalogWriter;
    private final StructureFixtureService structureFixtureService;
    private final StreamingStructureConversionService conversionService;
    private final CacheService cacheService;
    private final Sdmx21QueryNormalizer sdmx21QueryNormalizer;

    public InputStream getStructures(TranslatedStructureQuery query, RegistrySelectionResult selectedRegistry) {
        requireSupportedSetup(query);
        Structure structure = query.getStructure();
        String type = sdmx21QueryNormalizer.toSdmx21StructureType(structure.type()).toLowerCase(Locale.ROOT);
        String agency = sdmx21QueryNormalizer.toSdmx21PathSlot(structure.agency());
        String id = sdmx21QueryNormalizer.toSdmx21PathSlot(structure.id());
        String version = structure.version() != null ? sdmx21QueryNormalizer.toSdmx21PathSlot(structure.version()) : "latest";

        boolean agencyMatches = WILDCARDS.contains(agency) || StatCanIdentifiers.AGENCY.equals(agency);
        if (WILDCARDS.contains(id)) {
            if (!"dataflow".equals(type)) {
                throw new StructureListNotSupportedException(String.format("Statistics Canada structures are served one table at a time; listing every %s is not supported. Request one artefact by ID, for example %s/StatCan/%s.", type, type, exampleId(type)));
            }
            if (!agencyMatches) {
                return new ByteArrayInputStream(emptyStructureMessage());
            }
            return new ByteArrayInputStream(getCatalog(selectedRegistry));
        }

        String table = identifiers.tableOf(type, id).orElseThrow(() -> new StructureNotFoundException(String.format("No Statistics Canada %s '%s'. IDs carry the eight-digit table number, for example %s.", type, id, exampleId(type))));
        if (!agencyMatches) {
            throw new StructureNotFoundException(String.format("No %s '%s' for agency '%s'; Statistics Canada artefacts belong to agency %s.", type, id, agency, StatCanIdentifiers.AGENCY));
        }
        if (!ACCEPTED_VERSIONS.contains(version)) {
            throw new StructureNotFoundException(String.format("No Statistics Canada %s '%s' in version %s; every Statistics Canada artefact has version %s.", type, id, version, StatCanIdentifiers.VERSION));
        }

        SdmxBeans bundle = conversionService.parseStructures(new ByteArrayInputStream(getPreparedBundle(selectedRegistry, table)), SdmxFormat.XML_STRUCTURE_2_1);
        MaintainableBean target = findTarget(bundle, type, id);
        Set<MaintainableBean> selected = select(bundle, target, query.getReferences());

        SdmxBeans result = new SdmxBeansImpl();
        result.addIdentifiables(selected);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.convert(result, out, MediaType.parseMediaType(SdmxMediaTypes.STRUCTURE_XML_2_1));
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * Runtime copies of the {@code ConfigValidator} rules: the validator runs only in the config
     * server, and the proxy's own config load does not call it.
     */
    private void requireSupportedSetup(TranslatedStructureQuery query) {
        if (query.getVersionConfiguration().getSdmxVersion() != SdmxVersion.SDMX_2_1) {
            throw new UnexpectedStateException("The Statistics Canada structure source requires sdmxVersion SDMX_2_1, registry " + query.getRegistryConfiguration().getName() + " declares " + query.getVersionConfiguration().getSdmxVersion());
        }
        if (query.getRegistryReturnFormat() != SdmxFormat.XML_STRUCTURE_2_1) {
            throw new UnexpectedStateException("The Statistics Canada structure source answers XML_STRUCTURE_2_1 only, registry " + query.getRegistryConfiguration().getName() + " requested " + query.getRegistryReturnFormat() + "; set structureEndpointConfig.supportedFormats to [XML_STRUCTURE_2_1]");
        }
    }

    private byte[] getCatalog(RegistrySelectionResult selectedRegistry) {
        boolean includeArchived = Boolean.parseBoolean(adapterSetting(selectedRegistry, INCLUDE_ARCHIVED));
        String key = String.format(CATALOG_CACHE_KEY, selectedRegistry.getRegistryConfiguration().getName(), includeArchived);
        return cacheService.getRawStructures(key).orElseGet(() -> {
            byte[] catalog = buildCatalog(selectedRegistry, includeArchived);
            cacheService.putRawStructures(key, catalog);
            return catalog;
        });
    }

    @SneakyThrows
    private byte[] buildCatalog(RegistrySelectionResult selectedRegistry, boolean includeArchived) {
        try (InputStream cubes = sdmxApiClientProvider.getClient(StatCanWdsClient.class, adapterSetting(selectedRegistry, CATALOG_URL), selectedRegistry).getAllCubesListLite()) {
            return catalogWriter.write(cubes, includeArchived);
        }
    }

    private byte[] getPreparedBundle(RegistrySelectionResult selectedRegistry, String table) {
        String key = String.format(BUNDLE_CACHE_KEY, selectedRegistry.getRegistryConfiguration().getName(), table);
        return cacheService.getRawStructures(key).orElseGet(() -> {
            byte[] bundle = prepareBundle(selectedRegistry, table);
            cacheService.putRawStructures(key, bundle);
            return bundle;
        });
    }

    @SneakyThrows
    private byte[] prepareBundle(RegistrySelectionResult selectedRegistry, String table) {
        byte[] raw;
        try (InputStream in = sdmxApiClientProvider.getClient(StatCanStructureClient.class, selectedRegistry.getVersionConfiguration().getStructureEndpointConfig().getUrl(), selectedRegistry).getBundle(identifiers.dsdIdOf(table))) {
            raw = in.readAllBytes();
        }
        byte[] renamed = renamer.rename(raw, table);
        StructureEndpointConfiguration structureConfig = selectedRegistry.getVersionConfiguration().getStructureEndpointConfig();
        try (InputStream fixed = structureFixtureService.applyFixtures(new ByteArrayInputStream(renamed), SdmxFormat.XML_STRUCTURE_2_1, structureConfig.getFixtures())) {
            byte[] prepared = fixed.readAllBytes();
            log.debug("Prepared Statistics Canada bundle for table {}: {} bytes raw, {} bytes prepared", table, raw.length, prepared.length);
            return prepared;
        }
    }

    private static String adapterSetting(RegistrySelectionResult selectedRegistry, String key) {
        Map<String, String> adapterConfig = selectedRegistry.getRegistryConfiguration().getAdapterConfig();
        return adapterConfig != null ? adapterConfig.get(key) : null;
    }

    private MaintainableBean findTarget(SdmxBeans bundle, String type, String id) {
        return maintainables(bundle).stream()
                .filter(bean -> typeOf(bean).equals(type) && bean.getId().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new StructureNotFoundException(String.format("Statistics Canada has no %s '%s'.", type, id)));
    }

    /**
     * The bundle holds one dataflow and one DSD, so references are resolved against the bundle
     * alone, with no registry-wide graph walk. A null {@code references} counts as {@code none},
     * {@code ancestors} as transitive {@code parents}, and a structure type name selects the
     * artefacts of that type among the target's ancestors and descendants.
     */
    private Set<MaintainableBean> select(SdmxBeans bundle, MaintainableBean target, String references) {
        List<MaintainableBean> all = maintainables(bundle);
        Set<MaintainableBean> result = new LinkedHashSet<>();
        result.add(target);
        String refs = references != null ? references.toLowerCase(Locale.ROOT) : "none";
        switch (refs) {
            case "none" -> {
            }
            case "parents" -> result.addAll(referencing(all, target));
            case "ancestors" -> result.addAll(closure(all, target, false));
            case "parentsandsiblings" -> result.addAll(parentsAndSiblings(all, target));
            case "children" -> result.addAll(referencedBy(all, target));
            case "descendants" -> result.addAll(closure(all, target, true));
            case "all" -> {
                result.addAll(parentsAndSiblings(all, target));
                result.addAll(closure(all, target, true));
            }
            default -> {
                if (STRUCTURE_TYPES.contains(refs)) {
                    Set<MaintainableBean> related = new LinkedHashSet<>(closure(all, target, false));
                    related.addAll(closure(all, target, true));
                    related.stream().filter(bean -> typeOf(bean).equals(refs)).forEach(result::add);
                }
            }
        }
        return result;
    }

    private Set<MaintainableBean> parentsAndSiblings(List<MaintainableBean> all, MaintainableBean target) {
        Set<MaintainableBean> parents = referencing(all, target);
        Set<MaintainableBean> result = new LinkedHashSet<>(parents);
        parents.forEach(parent -> result.addAll(referencedBy(all, parent)));
        return result;
    }

    private Set<MaintainableBean> closure(List<MaintainableBean> all, MaintainableBean start, boolean downwards) {
        Set<MaintainableBean> seen = new LinkedHashSet<>();
        Deque<MaintainableBean> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            MaintainableBean current = queue.poll();
            Set<MaintainableBean> next = downwards ? referencedBy(all, current) : referencing(all, current);
            for (MaintainableBean bean : next) {
                if (bean != start && seen.add(bean)) {
                    queue.add(bean);
                }
            }
        }
        return seen;
    }

    /** The bundle artefacts that {@code bean} references directly. */
    private Set<MaintainableBean> referencedBy(List<MaintainableBean> all, MaintainableBean bean) {
        return all.stream().filter(candidate -> candidate != bean && references(bean, candidate)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The bundle artefacts that reference {@code bean} directly. */
    private Set<MaintainableBean> referencing(List<MaintainableBean> all, MaintainableBean bean) {
        return all.stream().filter(candidate -> candidate != bean && references(candidate, bean)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean references(MaintainableBean from, MaintainableBean to) {
        // sdmx-core upper-cases maintainable IDs but not the IDs inside references.
        return from.getCrossReferences().stream().anyMatch(ref -> ref.getMaintainableStructureType() == to.getStructureType() && to.getId().equalsIgnoreCase(ref.getReference().getMaintainableId()));
    }

    /** Dataflows, DSDs, codelists, and concept schemes; the agency scheme is left out. */
    private List<MaintainableBean> maintainables(SdmxBeans bundle) {
        List<MaintainableBean> result = new ArrayList<>();
        result.addAll(bundle.getDataflows());
        result.addAll(bundle.getDataStructures());
        result.addAll(bundle.getCodelists());
        result.addAll(bundle.getConceptSchemes());
        return result;
    }

    private String typeOf(MaintainableBean bean) {
        return switch (bean.getStructureType()) {
            case DATAFLOW -> "dataflow";
            case DSD -> "datastructure";
            case CODE_LIST -> "codelist";
            case CONCEPT_SCHEME -> "conceptscheme";
            default -> bean.getStructureType().name().toLowerCase(Locale.ROOT);
        };
    }

    private String exampleId(String type) {
        return switch (type) {
            case "datastructure" -> identifiers.dsdIdOf("17100005");
            case "codelist" -> identifiers.renamedCodelistId("17100005", "CL_Geography");
            case "conceptscheme" -> identifiers.renamedConceptSchemeId("17100005", "CONCEPTS");
            default -> identifiers.dataflowIdOf("17100005");
        };
    }

    private byte[] emptyStructureMessage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.convert(new SdmxBeansImpl(), out, MediaType.parseMediaType(SdmxMediaTypes.STRUCTURE_XML_2_1));
        return out.toByteArray();
    }
}
