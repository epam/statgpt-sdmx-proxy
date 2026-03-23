package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Code;
import com.epam.jsdmx.infomodel.sdmx30.CodeImpl;
import com.epam.jsdmx.infomodel.sdmx30.Codelist;
import com.epam.jsdmx.infomodel.sdmx30.CodelistImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.date.SdmxDate;
import io.sdmx.api.date.SdmxPeriod;
import io.sdmx.api.sdmx.model.beans.codelist.CodeBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.time.DateFormatUtils;

import java.util.List;

@RequiredArgsConstructor
public class CodelistMapper implements Mapper<CodelistBean> {

    private final TextMapper textMapper;
    private final AnnotationMapper annotationMapper;

    @Override
    public Codelist map(CodelistBean codelist) {
        var g = new CodelistImpl();
        g.setId(codelist.getId());
        g.setOrganizationId(codelist.getAgencyId());
        g.setVersion(Version.createFromString(codelist.getVersion().toString()));
        g.setName(textMapper.map(codelist.getNames()));
        g.setDescription(textMapper.map(codelist.getDescriptions()));
        g.setItems(mapItems(codelist.getItems()));
        //TODO CHECK IF NEEDED
        //        g.setAttributeDefinitions(getSystemAttributes());
        g.setAnnotations(annotationMapper.map(codelist.getAnnotations()));
        SdmxPeriod validityPeriod = codelist.getValidityPeriod();

        if (validityPeriod == null) {
            return g;
        }

        SdmxDate startDate = validityPeriod.getStartPeriod();
        SdmxDate endDate = validityPeriod.getEndPeriod();
        if (startDate != null && startDate.getDate() != null) {
            g.setValidFrom(DateFormatUtils.format(startDate.getDate(), "yyyy-MM-dd"));
        }
        if (endDate != null && endDate.getDate() != null) {
            g.setValidTo(DateFormatUtils.format(endDate.getDate(), "yyyy-MM-dd"));
        }
        return g;
    }

    private List<Code> mapItems(List<CodeBean> items) {
        return StreamUtils.streamOfNullable(items)
                .map(this::mapItem)
                .toList();
    }

    private Code mapItem(CodeBean codeBean) {
        var term = new CodeImpl();
        term.setId(codeBean.getId());
        term.setName(textMapper.map(codeBean.getNames()));
        term.setDescription(textMapper.map(codeBean.getDescriptions()));
        term.setAnnotations(annotationMapper.map(codeBean.getAnnotations()));
        return term;
    }
}
