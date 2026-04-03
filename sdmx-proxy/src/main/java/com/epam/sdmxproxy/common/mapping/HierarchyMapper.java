package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.HierarchicalCode;
import com.epam.jsdmx.infomodel.sdmx30.HierarchicalCodeImpl;
import com.epam.jsdmx.infomodel.sdmx30.Hierarchy;
import com.epam.jsdmx.infomodel.sdmx30.HierarchyImpl;
import com.epam.jsdmx.infomodel.sdmx30.IdentifiableArtefactReferenceImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.sdmx.model.beans.codelist.HierarchicalCodeBean;
import io.sdmx.api.sdmx.model.beans.codelist.HierarchyBean;
import io.sdmx.im.beans.codelist.HierarchicalCodeBeanImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class HierarchyMapper implements Mapper<HierarchyBean> {

    private static final Field LEVEL_REF_FIELD = initLevelRefField();

    private final TextMapper textMapper;
    private final AnnotationMapper annotationMapper;

    private static Field initLevelRefField() {
        try {
            Field field = HierarchicalCodeBeanImpl.class.getDeclaredField("levelRef");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            log.warn("Could not find levelRef field in HierarchicalCodeBeanImpl — level references may be lost for hierarchies without formal levels", e);
            return null;
        }
    }

    @Override
    public Hierarchy map(HierarchyBean bean) {
        var h = new HierarchyImpl();
        h.setId(bean.getId());
        h.setOrganizationId(bean.getAgencyId());
        h.setVersion(Version.createFromString(bean.getVersion().toString()));
        h.setName(textMapper.map(bean.getNames()));
        h.setDescription(textMapper.map(bean.getDescriptions()));
        h.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        h.setHasFormalLevels(bean.hasFormalLevels());
        h.setCodes(mapHierarchicalCodes(bean.getHierarchicalCodeBeans()));
        return h;
    }

    private List<HierarchicalCode> mapHierarchicalCodes(List<HierarchicalCodeBean> beans) {
        return StreamUtils.streamOfNullable(beans)
                .map(this::mapHierarchicalCode)
                .toList();
    }

    private HierarchicalCode mapHierarchicalCode(HierarchicalCodeBean bean) {
        var hc = new HierarchicalCodeImpl();
        hc.setId(bean.getId());
        if (bean.getCodeReference() != null && bean.getCodeReference().getTargetUrn() != null) {
            hc.setCode(new IdentifiableArtefactReferenceImpl(bean.getCodeReference().getTargetUrn()));
        }
        String levelId = extractLevelReference(bean);
        if (levelId != null) {
            hc.setLevelId(levelId);
        }
        hc.setHierarchicalCodes(mapHierarchicalCodes(bean.getCodeRefs()));
        return hc;
    }

    /**
     * Extracts the level reference from a HierarchicalCodeBean.
     *
     * <p>Per the SDMX-JSON 2.0.0 spec, the "level" field in a hierarchical code is a plain idType
     * string (e.g. "0", "L1"). sdmx-core stores this in the private {@code levelRef} field of
     * {@link HierarchicalCodeBeanImpl}, but {@code getLevel(false)} returns null when the hierarchy
     * has no formal Level objects to resolve against — and the mutable round-trip via
     * {@code getMutableInstance()} also loses it for the same reason.
     *
     * <p>Reflective access to the raw field is the only way to preserve the level reference through
     * the conversion pipeline.
     */
    private String extractLevelReference(HierarchicalCodeBean bean) {
        if (bean.getLevel(false) != null) {
            return bean.getLevel(false).getId();
        }
        if (LEVEL_REF_FIELD != null && bean instanceof HierarchicalCodeBeanImpl) {
            try {
                return (String) LEVEL_REF_FIELD.get(bean);
            } catch (IllegalAccessException e) {
                log.warn("Could not extract levelRef from HierarchicalCodeBeanImpl", e);
            }
        }
        return null;
    }
}
