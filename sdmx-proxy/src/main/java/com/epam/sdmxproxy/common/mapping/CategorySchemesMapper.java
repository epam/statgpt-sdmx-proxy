package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Category;
import com.epam.jsdmx.infomodel.sdmx30.CategoryImpl;
import com.epam.jsdmx.infomodel.sdmx30.CategoryScheme;
import com.epam.jsdmx.infomodel.sdmx30.CategorySchemeImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.sdmx.model.beans.categoryscheme.CategoryBean;
import io.sdmx.api.sdmx.model.beans.categoryscheme.CategorySchemeBean;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class CategorySchemesMapper implements Mapper<CategorySchemeBean> {

    private final AnnotationMapper annotationMapper;
    private final TextMapper textMapper;

    @Override
    public CategoryScheme map(CategorySchemeBean bean) {
        var categoryScheme = new CategorySchemeImpl();
        categoryScheme.setOrganizationId(bean.getAgencyId());
        categoryScheme.setId(bean.getId());
        categoryScheme.setVersion(Version.createFromString(bean.getVersion().toString()));
        categoryScheme.setName(textMapper.map(bean.getNames()));
        categoryScheme.setDescription(textMapper.map(bean.getDescriptions()));
        categoryScheme.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        categoryScheme.setUri(bean.getUri());
        categoryScheme.setServiceUrl(bean.getServiceURL());
        categoryScheme.setStructureUrl(bean.getStructureURL());
        categoryScheme.setItems(mapItems(bean.getItems()));
        return categoryScheme;
    }

    private List<Category> mapItems(List<CategoryBean> items) {
        return StreamUtils.streamOfNullable(items)
                .map(this::mapItem)
                .toList();
    }

    private Category mapItem(CategoryBean categoryBean) {
        var category = new CategoryImpl();
        category.setId(categoryBean.getId());
        category.setName(textMapper.map(categoryBean.getNames()));
        category.setDescription(textMapper.map(categoryBean.getDescriptions()));
        category.setAnnotations(annotationMapper.map(categoryBean.getAnnotations()));
        category.setUri(categoryBean.getUri());
        category.setHierarchy(mapItems(categoryBean.getItems()));
        return category;
    }
}
