package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryAdapterType;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.exception.StructureListNotSupportedException;
import com.epam.sdmxproxy.exception.StructureNotFoundException;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import com.epam.sdmxproxy.registry.api.SdmxApiClientProvider;
import com.epam.sdmxproxy.services.adapter.MatchAllKeyExpander;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.cache.CacheService;
import com.epam.sdmxproxy.services.fixture.data.RepairEmptyDataSetXmlDataFixture;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptBean;
import io.sdmx.im.beans.container.SdmxBeansImpl;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the Statistics Canada structure source against the probed bundles and the real sdmx-core
 * reader and writer -- see design 043.
 */
@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
class StatCanStructureSourceTest {

    @Autowired
    private StreamingStructureConversionService conversionService;
    @Autowired
    private StreamingDataConversionService dataConversionService;
    @Autowired
    private StructureFixtureService structureFixtureService;

    private StatCanStructureClient structureClient;
    private StatCanStructureSource source;
    private RegistrySelectionResult registry;

    @BeforeEach
    void setUp() {
        SdmxApiClientProvider clientProvider = mock(SdmxApiClientProvider.class);
        structureClient = mock(StatCanStructureClient.class);
        StatCanWdsClient wdsClient = mock(StatCanWdsClient.class);
        when(clientProvider.getClient(eq(StatCanStructureClient.class), eq("https://example.com/sdmx/"), any())).thenReturn(structureClient);
        when(clientProvider.getClient(eq(StatCanWdsClient.class), eq("https://example.com/wds/"), any())).thenReturn(wdsClient);
        when(structureClient.getBundle(anyString())).thenAnswer(invocation -> {
            InputStream bundle = getClass().getResourceAsStream(invocation.getArgument(0, String.class) + ".xml");
            if (bundle == null) {
                throw new IllegalStateException("No probed bundle for " + invocation.getArgument(0));
            }
            return bundle;
        });
        when(wdsClient.getAllCubesListLite()).thenAnswer(invocation -> getClass().getResourceAsStream("getAllCubesListLite-sample.json"));

        Map<String, byte[]> cache = new HashMap<>();
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.getRawStructures(anyString())).thenAnswer(invocation -> Optional.ofNullable(cache.get(invocation.getArgument(0, String.class))));
        doAnswer(invocation -> cache.put(invocation.getArgument(0), invocation.getArgument(1))).when(cacheService).putRawStructures(anyString(), any());

        StatCanIdentifiers identifiers = new StatCanIdentifiers();
        source = new StatCanStructureSource(clientProvider, identifiers, new StatCanBundleRenamer(identifiers), new StatCanCatalogWriter(identifiers), structureFixtureService, conversionService, cacheService, new Sdmx21QueryNormalizerImpl());
        registry = registry();
    }

    @Test
    void shouldReturnCatalogForAllDataflows() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "all", "all", "latest", null), registry));

        assertThat(beans.getDataflows()).hasSize(12);
        assertThat(ids(beans.getDataflows())).contains("DF_17100005", "DF_18100004").doesNotContain("DF_10100001");
        assertThat(beans.getDataflows().iterator().next().getDataStructureRef().getReference().getMaintainableId()).startsWithIgnoringCase("Data_Structure_");
    }

    @Test
    void shouldAnswerEmptyCatalogForOtherAgency() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "ESTAT", "all", "latest", null), registry));

        assertThat(beans.getDataflows()).isEmpty();
    }

    @Test
    void shouldReturnDataflowWithDsdForDatastructureReference() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "StatCan", "DF_17100005", "latest", "datastructure"), registry));

        assertThat(ids(beans.getDataflows())).containsExactly("DF_17100005");
        assertThat(ids(beans.getDataStructures())).containsExactly("DATA_STRUCTURE_17100005");
        assertThat(beans.getCodelists()).isEmpty();
        assertThat(beans.getConceptSchemes()).isEmpty();
    }

    @Test
    void shouldReturnWholeBundleForDescendants() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "StatCan", "DF_17100005", "1.0", "descendants"), registry));

        assertThat(beans.getDataStructures()).hasSize(1);
        assertThat(beans.getCodelists()).hasSize(10);
        assertThat(ids(beans.getConceptSchemes())).containsExactly("CONCEPTS_17100005");
        assertThat(beans.getAgenciesSchemes()).isEmpty();
    }

    @Test
    void shouldReturnOneCodelist() {
        SdmxBeans beans = parse(source.getStructures(query("codelist", "StatCan", "CL_17100005_Geography", "1.0", null), registry));

        assertThat(beans.getCodelists()).hasSize(1);
        assertThat(beans.getCodelists().iterator().next().getItems()).hasSize(15);
        assertThat(beans.getDataStructures()).isEmpty();
    }

    @Test
    void shouldTreatAncestorsAsParents() {
        SdmxBeans beans = parse(source.getStructures(query("codelist", "StatCan", "CL_17100005_Geography", "1.0", "ancestors"), registry));

        assertThat(ids(beans.getDataStructures())).containsExactly("DATA_STRUCTURE_17100005");
        assertThat(ids(beans.getDataflows())).containsExactly("DF_17100005");
    }

    @Test
    void shouldReturnConceptSchemeWithCoreRepresentations() {
        SdmxBeans beans = parse(source.getStructures(query("conceptscheme", "StatCan", "CONCEPTS_17100005", "1.0", "none"), registry));

        assertThat(beans.getConceptSchemes()).hasSize(1);
        List<ConceptBean> concepts = beans.getConceptSchemes().iterator().next().getItems();
        assertThat(concepts).hasSize(14);
        assertThat(concepts).allSatisfy(concept -> assertThat(concept.getRepresentation()).as("concept %s", concept.getId()).isNotNull());
        ConceptBean geography = concepts.stream().filter(concept -> concept.getId().equals("Geography")).findFirst().orElseThrow();
        assertThat(geography.getRepresentation().getRepresentation().getReference().getMaintainableId()).isEqualToIgnoringCase("CL_17100005_Geography");
    }

    @Test
    void shouldGiveObsValueARepresentation() {
        SdmxBeans beans = parse(source.getStructures(query("datastructure", "StatCan", "Data_Structure_17100005", "1.0", null), registry));

        assertThat(beans.getDataStructures().iterator().next().getPrimaryMeasure().getRepresentation()).isNotNull();
    }

    @Test
    void shouldProduceDisjointCodelistUrnsForTwoTables() {
        Set<String> first = urns(parse(source.getStructures(query("datastructure", "StatCan", "Data_Structure_17100005", "1.0", "children"), registry)).getCodelists());
        Set<String> second = urns(parse(source.getStructures(query("datastructure", "StatCan", "Data_Structure_18100004", "1.0", "children"), registry)).getCodelists());

        assertThat(first).hasSize(10);
        assertThat(second).isNotEmpty();
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    void shouldDownloadBundleOncePerTable() {
        source.getStructures(query("dataflow", "StatCan", "DF_17100005", "latest", "datastructure"), registry);
        source.getStructures(query("conceptscheme", "StatCan", "CONCEPTS_17100005", "1.0", null), registry);
        source.getStructures(query("codelist", "StatCan", "CL_17100005_Geography", "1.0", null), registry);
        source.getStructures(query("codelist", "StatCan", "CL_17100005_Gender", "1.0", null), registry);

        verify(structureClient, times(1)).getBundle("Data_Structure_17100005");
    }

    @Test
    void shouldAnswer501ForAllCodelists() {
        assertThatThrownBy(() -> source.getStructures(query("codelist", "StatCan", "all", "latest", null), registry))
                .isInstanceOf(StructureListNotSupportedException.class);
    }

    @Test
    void shouldAnswer404ForUnknownVersion() {
        assertThatThrownBy(() -> source.getStructures(query("codelist", "StatCan", "CL_17100005_Geography", "2.0", null), registry))
                .isInstanceOf(StructureNotFoundException.class);
    }

    @Test
    void shouldAnswer404ForBareCodelistId() {
        assertThatThrownBy(() -> source.getStructures(query("codelist", "StatCan", "CL_Geography", "1.0", null), registry))
                .isInstanceOf(StructureNotFoundException.class)
                .hasMessageContaining("CL_17100005_Geography");
    }

    @Test
    void shouldAnswer404ForUnknownCodelistInKnownTable() {
        assertThatThrownBy(() -> source.getStructures(query("codelist", "StatCan", "CL_17100005_Nothing", "1.0", null), registry))
                .isInstanceOf(StructureNotFoundException.class);
    }

    @Test
    void shouldFailFastOnNonXmlReturnFormat() {
        TranslatedStructureQuery query = query("dataflow", "StatCan", "DF_17100005", "1.0", null);
        query.setRegistryReturnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0);

        assertThatThrownBy(() -> source.getStructures(query, registry)).isInstanceOf(UnexpectedStateException.class);
    }

    @Test
    void shouldEnumerateSmallestCodelist() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "StatCan", "DF_17100005", "1.0", "descendants"), registry));

        assertThat(new MatchAllKeyExpander().expand(beans)).isEqualTo("*.2+3+1.*");
    }

    @Test
    void shouldEnumerateGeographyForCpi() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "StatCan", "DF_18100004", "1.0", "descendants"), registry));

        String key = new MatchAllKeyExpander().expand(beans);
        assertThat(key).endsWith(".*");
        assertThat(key.split("\\.")[0].split("\\+")).hasSize(30);
    }

    @Test
    void shouldFailWithoutEnumeratedDimension() {
        SdmxBeans withoutCodelists = new SdmxBeansImpl();
        withoutCodelists.addIdentifiables(parse(source.getStructures(query("datastructure", "StatCan", "Data_Structure_17100005", "1.0", null), registry)).getDataStructures());

        assertThatThrownBy(() -> new MatchAllKeyExpander().expand(withoutCodelists)).isInstanceOf(UnexpectedStateException.class);
    }

    @Test
    @SneakyThrows
    void shouldConvertRepairedEmptyBodyToJson20() {
        SdmxBeans beans = parse(source.getStructures(query("dataflow", "StatCan", "DF_17100005", "1.0", "descendants"), registry));
        RepairEmptyDataSetXmlDataFixture repair = new RepairEmptyDataSetXmlDataFixture();
        Map<String, SdmxFormat> bodies = Map.of("empty_ss.xml", SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, "empty_generic.xml", SdmxFormat.XML_GENERIC_DATA_2_1);

        for (Map.Entry<String, SdmxFormat> body : bodies.entrySet()) {
            InputStream raw = RepairEmptyDataSetXmlDataFixture.class.getResourceAsStream("statcan/" + body.getKey());
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            dataConversionService.convert(repair.apply(raw, beans, Map.of()), out, beans, body.getValue(), MediaType.parseMediaType(SdmxMediaTypes.DATA_JSON_2_0_0));

            String json = out.toString(StandardCharsets.UTF_8);
            assertThat(json).as("JSON 2.0 conversion of %s", body.getKey()).contains("\"dataSets\"").doesNotContain("\"series\":{\"0");
        }
    }

    private SdmxBeans parse(InputStream structures) {
        return conversionService.parseStructures(structures, SdmxFormat.XML_STRUCTURE_2_1);
    }

    private static Set<String> ids(Set<? extends IdentifiableBean> beans) {
        return beans.stream().map(IdentifiableBean::getId).collect(Collectors.toSet());
    }

    private static Set<String> urns(Set<? extends IdentifiableBean> beans) {
        return beans.stream().map(bean -> bean.getUrn().toString()).collect(Collectors.toSet());
    }

    private TranslatedStructureQuery query(String type, String agency, String id, String version, String references) {
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registry.getRegistryConfiguration())
                .versionConfiguration(registry.getVersionConfiguration())
                .structure(new Structure(type, agency, id, version))
                .references(references)
                .detail("full")
                .contentType(MediaType.parseMediaType(SdmxMediaTypes.STRUCTURE_XML_2_1))
                .registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private static RegistrySelectionResult registry() {
        FixtureConfiguration<StructureFixtureType> fixture = new FixtureConfiguration<>();
        fixture.setType(StructureFixtureType.FILL_MISSING_REPRESENTATIONS);

        StructureEndpointConfiguration structureConfig = new StructureEndpointConfiguration();
        structureConfig.setUrl("https://example.com/sdmx/");
        structureConfig.setSupportedFormats(List.of(SdmxFormat.XML_STRUCTURE_2_1));
        structureConfig.setFixtures(List.of(fixture));

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(SdmxVersion.SDMX_2_1);
        versionConfig.setStructureEndpointConfig(structureConfig);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("StatCan");
        registryConfig.setAdapter(RegistryAdapterType.STATCAN);
        registryConfig.setAdapterConfig(Map.of(StatCanStructureSource.CATALOG_URL, "https://example.com/wds/"));

        return RegistrySelectionResult.builder().registryConfiguration(registryConfig).versionConfiguration(versionConfig).build();
    }
}
