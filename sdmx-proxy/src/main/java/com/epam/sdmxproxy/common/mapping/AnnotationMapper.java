package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Annotation;
import com.epam.jsdmx.infomodel.sdmx30.AnnotationImpl;
import com.epam.jsdmx.infomodel.sdmx30.InternationalUri;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.sdmx.model.beans.base.AnnotationBean;
import io.sdmx.api.sdmx.model.mutable.base.AnnotationMutableBean;
import io.sdmx.im.mutable.base.AnnotationMutableBeanImpl;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toCollection;

@RequiredArgsConstructor
public class AnnotationMapper {

    private final TextMapper textMapper;
    private final Predicate<String> customPredicate;

    public AnnotationMapper(TextMapper textMapper) {
        this.textMapper = textMapper;
        this.customPredicate = null;
    }

    public List<Annotation> map(List<AnnotationBean> annotations) {
        return StreamUtils.streamOfNullable(annotations)
                .filter(a -> customPredicate == null || customPredicate.test(a.getId()))
                .map(this::map)
                .collect(toCollection(ArrayList::new)); // explicitly state that we want mutable collection here
    }

    private Annotation map(AnnotationBean annotation) {
        var a = new AnnotationImpl();
        a.setId(annotation.getId());
        a.setTitle(annotation.getTitle());
        a.setUrl(Optional.ofNullable(annotation.getUri()).map(InternationalUri::new).orElse(null));
        a.setText(Optional.ofNullable(annotation.getText()).map(textMapper::map).orElse(null));
        a.setType(annotation.getType());
        return a;
    }

    public List<AnnotationMutableBean> mapToBeans(List<Annotation> annotations) {
        return StreamUtils.streamOfNullable(annotations)
                .map(this::map)
                .collect(toCollection(ArrayList::new));
    }

    private AnnotationMutableBean map(Annotation annotation) {
        var a = new AnnotationMutableBeanImpl();
        a.setId(annotation.getId());
        a.setTitle(annotation.getTitle());
        a.setUri(String.valueOf(Optional.ofNullable(annotation.getUrl()).map(InternationalUri::getForDefaultLocale).orElse(null)));
        a.setText(textMapper.map(annotation.getText()));
        a.setType(annotation.getType());
        return a;
    }
}
