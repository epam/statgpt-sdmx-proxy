package com.epam.sdmxproxy.services.translator;

import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.exception.UnsupportedContextException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design 041 / P9. The accepted set is scope-dependent, so every case names the resource id it
 * resolves against.
 */
class ReferencesResolverTest {

    private ReferencesResolver sut;
    private StructureEndpointConfiguration estat;

    @BeforeEach
    void setUp() {
        sut = new ReferencesResolver();
        estat = new StructureEndpointConfiguration();
        estat.setSupportedReferences(Set.of("none", "children", "descendants"));
        estat.setSupportedReferencesForAll(Set.of("none"));
        estat.setReferencesDowngrade(Map.of("datastructure", "descendants", "parents", "none", "parentsandsiblings", "none", "all", "descendants", "children", "none", "descendants", "none"));
    }

    @Test
    void shouldPassAcceptedValueThrough() {
        assertEquals("descendants", sut.resolve("descendants", "NAMA_10_GDP", estat));
        assertEquals("children", sut.resolve("children", "NAMA_10_GDP", estat));
        assertEquals("none", sut.resolve("none", "NAMA_10_GDP", estat));
    }

    @Test
    void shouldDowngradeDatastructureToDescendants() {
        assertEquals("descendants", sut.resolve("datastructure", "NAMA_10_GDP", estat));
    }

    @Test
    void shouldUseAllScopeSetForWildcardResourceId() {
        assertEquals("none", sut.resolve("none", "*", estat));
        assertEquals("none", sut.resolve("none", "all", estat));
        assertEquals("none", sut.resolve("none", null, estat));
    }

    /**
     * The same value resolves differently by scope: accepted for one artefact, downgraded to
     * {@code none} over a wildcard, where Eurostat 2.1 answers 400 and Eurostat 3.0 silently drops
     * the references.
     */
    @Test
    void shouldDowngradeToNoneForWildcardScope() {
        assertEquals("descendants", sut.resolve("descendants", "NAMA_10_GDP", estat));
        assertEquals("none", sut.resolve("descendants", "*", estat));
        assertEquals("none", sut.resolve("children", "*", estat));
    }

    @Test
    void shouldRejectWhenNoDowngradeEntry() {
        estat.setReferencesDowngrade(Map.of());
        UnsupportedContextException thrown = assertThrows(UnsupportedContextException.class, () -> sut.resolve("datastructure", "NAMA_10_GDP", estat));
        assertTrue(thrown.getMessage().contains("datastructure"));
        assertTrue(thrown.getMessage().contains("descendants"));
    }

    @Test
    void shouldPassThroughWhenNoSupportedReferencesConfigured() {
        StructureEndpointConfiguration open = new StructureEndpointConfiguration();
        assertEquals("datastructure", sut.resolve("datastructure", "NAMA_10_GDP", open));
        assertEquals("parents", sut.resolve("parents", "*", open));
    }

    @Test
    void shouldPassThroughNullReferencesAndNullConfig() {
        assertNull(sut.resolve(null, "NAMA_10_GDP", estat));
        assertEquals("descendants", sut.resolve("descendants", "NAMA_10_GDP", null));
    }

    @Test
    void shouldFallBackToSupportedReferencesWhenAllScopeSetIsAbsent() {
        estat.setSupportedReferencesForAll(null);
        assertEquals("descendants", sut.resolve("descendants", "*", estat));
    }
}
