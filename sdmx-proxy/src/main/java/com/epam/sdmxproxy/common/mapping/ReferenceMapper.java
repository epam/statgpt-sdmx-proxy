package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.ArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.IdentifiableArtefactReferenceImpl;
import com.epam.jsdmx.infomodel.sdmx30.MaintainableArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.StructureClass;
import io.sdmx.api.sdmx.model.beans.base.IURNSingle;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;

public class ReferenceMapper {

    public ArtefactReference mapMaintainable(ICrossReferenceBean<?> ref, StructureClass structureClass) {
        final IURNSingle<?> maintainableReference = ref.getReference();
        return new MaintainableArtefactReference(
                maintainableReference.getMaintainableId(),
                maintainableReference.getAgencyId(),
                maintainableReference.getVersion().toString(),
                structureClass
        );
    }

    public ArtefactReference mapItem(ICrossReferenceBean<?> ref, StructureClass structureClass) {
        final IURNSingle<?> maintainableReference = ref.getReference();
        return new IdentifiableArtefactReferenceImpl(
                maintainableReference.getMaintainableId(),
                maintainableReference.getAgencyId(),
                maintainableReference.getVersion().toString(),
                structureClass,
                maintainableReference.getFullIdentifiableId()
        );
    }

}
