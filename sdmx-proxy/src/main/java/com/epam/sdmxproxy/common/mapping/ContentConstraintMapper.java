package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.AfterPeriodImpl;
import com.epam.jsdmx.infomodel.sdmx30.ArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.BeforePeriodImpl;
import com.epam.jsdmx.infomodel.sdmx30.ComponentValueImpl;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegion;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionImpl;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionKey;
import com.epam.jsdmx.infomodel.sdmx30.CubeRegionKeyImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataConstraint;
import com.epam.jsdmx.infomodel.sdmx30.DataConstraintImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataKey;
import com.epam.jsdmx.infomodel.sdmx30.DataKeyImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataKeySet;
import com.epam.jsdmx.infomodel.sdmx30.DataKeySetImpl;
import com.epam.jsdmx.infomodel.sdmx30.MemberSelection;
import com.epam.jsdmx.infomodel.sdmx30.MemberSelectionImpl;
import com.epam.jsdmx.infomodel.sdmx30.MemberValueImpl;
import com.epam.jsdmx.infomodel.sdmx30.RangePeriodImpl;
import com.epam.jsdmx.infomodel.sdmx30.ReleaseCalendar;
import com.epam.jsdmx.infomodel.sdmx30.ReleaseCalendarImpl;
import com.epam.jsdmx.infomodel.sdmx30.SelectionValue;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.TimeRangePeriodImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.date.SdmxDate;
import io.sdmx.api.sdmx.model.beans.base.TimeRangeBean;
import io.sdmx.api.sdmx.model.beans.registry.ConstrainedDataKeyBean;
import io.sdmx.api.sdmx.model.beans.registry.ConstraintAttachmentBean;
import io.sdmx.api.sdmx.model.beans.registry.ConstraintDataKeySetBean;
import io.sdmx.api.sdmx.model.beans.registry.ContentConstraintBean;
import io.sdmx.api.sdmx.model.beans.registry.CubeRegionBean;
import io.sdmx.api.sdmx.model.beans.registry.KeyValues;
import io.sdmx.api.sdmx.model.beans.registry.ReleaseCalendarBean;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
public class ContentConstraintMapper implements Mapper<ContentConstraintBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public DataConstraint map(ContentConstraintBean bean) {
        var constraint = new DataConstraintImpl();

        constraint.setOrganizationId(bean.getAgencyId());
        constraint.setId(bean.getId());
        constraint.setVersion(Version.createFromString(bean.getVersion().toString()));
        constraint.setName(textMapper.map(bean.getNames()));
        constraint.setDescription(textMapper.map(bean.getDescriptions()));
        constraint.setAnnotations(annotationMapper.map(bean.getAnnotations()));

        constraint.setReleaseCalendar(mapReleaseCalendar(bean.getReleaseCalendar()));
        constraint.setConstrainedArtefacts(mapConstrainedArtefacts(bean.getConstraintAttachment()));
        constraint.setCubeRegions(mapCubeRegions(bean.getExcludedCubeRegion(), bean.getIncludedCubeRegion()));
        constraint.setDataContentKeys(mapDataKeys(bean.getExcludedSeriesKeys(), bean.getIncludedSeriesKeys()));

        return constraint;
    }

    private List<ArtefactReference> mapConstrainedArtefacts(ConstraintAttachmentBean constraintAttachment) {
        if (constraintAttachment == null) {
            return null;
        }
        var artefactReferenceList = new ArrayList<ArtefactReference>();

        for (var ref : constraintAttachment.getStructureReference()) {
            artefactReferenceList.add(referenceMapper.mapMaintainable(ref, StructureClassImpl.DATAFLOW));
        }

        return artefactReferenceList;
    }

    private ReleaseCalendar mapReleaseCalendar(ReleaseCalendarBean releaseCalendarBean) {
        if (releaseCalendarBean == null) {
            return null;
        }
        var releaseCalendar = new ReleaseCalendarImpl();
        releaseCalendar.setOffset(releaseCalendarBean.getOffset());
        releaseCalendar.setTolerance(releaseCalendarBean.getTolerance());
        releaseCalendar.setPeriodicity(releaseCalendarBean.getPeriodicity());
        return releaseCalendar;
    }

    private List<CubeRegion> mapCubeRegions(CubeRegionBean excludedCubeRegionBean, CubeRegionBean includedCubeRegionBean) {
        if (excludedCubeRegionBean == null && includedCubeRegionBean == null) {
            return Collections.emptyList();
        }
        var cubeRegionList = new ArrayList<CubeRegion>();
        mapCubeRegion(cubeRegionList, excludedCubeRegionBean, false);
        mapCubeRegion(cubeRegionList, includedCubeRegionBean, true);

        return cubeRegionList;
    }

    private void mapCubeRegion(List<CubeRegion> cubeRegionList, CubeRegionBean cubeRegionBean, boolean included) {
        if (cubeRegionBean != null) {
            var cubeRegion = new CubeRegionImpl();
            cubeRegion.setIncluded(included);

            if (CollectionUtils.isNotEmpty(cubeRegionBean.getKeyValues())) {
                cubeRegion.setCubeRegionKeys(mapKeyValues(cubeRegionBean.getKeyValues(), included));
            }

            if (CollectionUtils.isNotEmpty(cubeRegionBean.getAttributeValues())) {
                cubeRegion.setMemberSelections(mapMemberSelections(cubeRegionBean.getAttributeValues(), included));
            }

            cubeRegionList.add(cubeRegion);
        }
    }

    private List<CubeRegionKey> mapKeyValues(List<KeyValues> keyValuesList, boolean included) {
        var cubeRegionKeyList = new ArrayList<CubeRegionKey>();

        for (var keyValue : keyValuesList) {
            var cubeRegionKey = new CubeRegionKeyImpl();
            cubeRegionKey.setComponentId(keyValue.getId());
            cubeRegionKey.setIncluded(included);
            cubeRegionKey.setSelectionValues(mapSelectionValues(keyValue.getValues(), keyValue.getTimeRange()));
            cubeRegionKeyList.add(cubeRegionKey);
        }

        return cubeRegionKeyList;
    }

    private List<MemberSelection> mapMemberSelections(List<KeyValues> attributesList, boolean included) {
        var memberSelectionList = new ArrayList<MemberSelection>();

        for (var attribute : attributesList) {
            var memberSelection = new MemberSelectionImpl();
            memberSelection.setComponentId(attribute.getId());
            memberSelection.setIncluded(included);
            memberSelection.setSelectionValues(mapSelectionValues(attribute.getValues(), attribute.getTimeRange()));
            memberSelectionList.add(memberSelection);
        }

        return memberSelectionList;
    }

    private List<SelectionValue> mapSelectionValues(Set<String> values, TimeRangeBean timeRange) {
        var selectionValues = new ArrayList<SelectionValue>();

        if (timeRange != null) {
            var timeValue = mapTimeRange(timeRange);
            if (timeValue != null) {
                selectionValues.add(timeValue);
            }
        }

        for (var value : values) {
            var memberValue = new MemberValueImpl();
            memberValue.setValue(value);
            selectionValues.add(memberValue);
        }

        return selectionValues;
    }

    private SelectionValue mapTimeRange(TimeRangeBean timeRange) {
        if (timeRange.getStartDate() != null && timeRange.getEndDate() != null) {
            var rangePeriod = new RangePeriodImpl();

            var start = new TimeRangePeriodImpl();
            start.setPeriod(parseDate(timeRange.getStartDate()));
            rangePeriod.setStartPeriod(start);

            var end = new TimeRangePeriodImpl();
            end.setPeriod(parseDate(timeRange.getEndDate()));
            rangePeriod.setEndPeriod(end);

            return rangePeriod;
        }

        if (timeRange.getStartDate() != null && timeRange.getEndDate() == null) {
            var beforePeriod = new BeforePeriodImpl();
            beforePeriod.setPeriod(parseDate(timeRange.getStartDate()));
            return beforePeriod;
        }

        if (timeRange.getStartDate() == null && timeRange.getEndDate() != null) {
            var afterPeriod = new AfterPeriodImpl();
            afterPeriod.setPeriod(parseDate(timeRange.getEndDate()));
            return afterPeriod;
        }

        return null;
    }

    private String parseDate(SdmxDate date) {
        return Optional.ofNullable(date.getDate())
                .map(Date::toInstant)
                .map(Instant::toString)
                .orElse(null);
    }

    private List<DataKeySet> mapDataKeys(ConstraintDataKeySetBean excludedSeriesKeys, ConstraintDataKeySetBean includedSeriesKeys) {
        if (excludedSeriesKeys == null && includedSeriesKeys == null) {
            return Collections.emptyList();
        }
        var dataKeySets = new ArrayList<DataKeySet>();
        mapDataKey(dataKeySets, excludedSeriesKeys, false);
        mapDataKey(dataKeySets, includedSeriesKeys, true);
        return dataKeySets;
    }

    private void mapDataKey(List<DataKeySet> dataKeySets, ConstraintDataKeySetBean constraintDataKeySetBean, boolean included) {
        if (constraintDataKeySetBean != null) {
            var dataKeySet = new DataKeySetImpl();
            dataKeySet.setIncluded(included);
            if (constraintDataKeySetBean.getConstrainedDataKeys() != null) {
                dataKeySet.setKeys(mapDataKeys(constraintDataKeySetBean.getConstrainedDataKeys()));
                dataKeySets.add(dataKeySet);
            }
        }
    }

    private List<DataKey> mapDataKeys(List<ConstrainedDataKeyBean> constrainedDataKeyBeans) {
        var dataKeyList = new ArrayList<DataKey>();
        for (var dataKey : constrainedDataKeyBeans) {
            for (var keyValue : dataKey.getKeyValues()) {
                var key = new DataKeyImpl();
                var componentValue = new ComponentValueImpl();
                componentValue.setComponentId(keyValue.getConcept());
                componentValue.setValue(keyValue.getCode());
                key.setKeyValues(List.of(componentValue));
                dataKeyList.add(key);
            }
        }

        return dataKeyList;
    }
}
