package com.epam.sdmxproxy.services.limit;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.EnumeratedListBean;
import io.sdmx.api.sdmx.model.beans.base.IURNSingle;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodeBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodelistSizeResolverTest {

    private final CodelistSizeResolver resolver = new CodelistSizeResolver();

    @Test
    void resolveSize_returnsCodelistSize_whenReferenceMatches() {
        CodelistBean codelist = mock(CodelistBean.class);
        @SuppressWarnings("unchecked")
        List<CodeBean> codes = (List<CodeBean>) (List<?>) List.of(
                mock(CodeBean.class), mock(CodeBean.class), mock(CodeBean.class));
        when(codelist.getItems()).thenReturn(codes);

        IURNSingle<? extends EnumeratedListBean> reference = mock(IURNSingle.class);
        when(reference.isMatch(any(IdentifiableBean.class))).thenReturn(true);

        ICrossReferenceBean<? extends EnumeratedListBean> codelistRef = mock(ICrossReferenceBean.class);
        doReturn(reference).when(codelistRef).getReference();

        RepresentationBean rep = mock(RepresentationBean.class);
        doReturn(codelistRef).when(rep).getRepresentation();

        DimensionBean dim = mock(DimensionBean.class);
        when(dim.getRepresentation()).thenReturn(rep);

        SdmxBeans beans = mock(SdmxBeans.class);
        when(beans.getCodelists()).thenReturn(Set.of(codelist));

        assertThat(resolver.resolveSize(dim, beans)).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void resolveSize_returnsEmpty_whenDimensionIsNull() {
        SdmxBeans beans = mock(SdmxBeans.class);
        assertThat(resolver.resolveSize(null, beans)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void resolveSize_returnsEmpty_whenDimensionHasNoRepresentation() {
        DimensionBean dim = mock(DimensionBean.class);
        when(dim.getRepresentation()).thenReturn(null);
        SdmxBeans beans = mock(SdmxBeans.class);

        assertThat(resolver.resolveSize(dim, beans)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void resolveSize_returnsEmpty_whenRepresentationHasNoCodelistRef() {
        RepresentationBean rep = mock(RepresentationBean.class);
        doReturn(null).when(rep).getRepresentation();
        DimensionBean dim = mock(DimensionBean.class);
        when(dim.getRepresentation()).thenReturn(rep);
        SdmxBeans beans = mock(SdmxBeans.class);

        assertThat(resolver.resolveSize(dim, beans)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void resolveSize_returnsEmpty_whenCodelistNotInBeans() {
        IURNSingle<? extends EnumeratedListBean> reference = mock(IURNSingle.class);
        when(reference.isMatch(any(IdentifiableBean.class))).thenReturn(false);

        ICrossReferenceBean<? extends EnumeratedListBean> codelistRef = mock(ICrossReferenceBean.class);
        doReturn(reference).when(codelistRef).getReference();

        RepresentationBean rep = mock(RepresentationBean.class);
        doReturn(codelistRef).when(rep).getRepresentation();
        DimensionBean dim = mock(DimensionBean.class);
        when(dim.getRepresentation()).thenReturn(rep);

        CodelistBean unrelated = mock(CodelistBean.class);
        SdmxBeans beans = mock(SdmxBeans.class);
        when(beans.getCodelists()).thenReturn(Set.of(unrelated));

        assertThat(resolver.resolveSize(dim, beans)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void resolveSize_returnsEmpty_whenBeansIsNull() {
        DimensionBean dim = mock(DimensionBean.class);
        assertThat(resolver.resolveSize(dim, null)).isEqualTo(OptionalInt.empty());
    }
}
