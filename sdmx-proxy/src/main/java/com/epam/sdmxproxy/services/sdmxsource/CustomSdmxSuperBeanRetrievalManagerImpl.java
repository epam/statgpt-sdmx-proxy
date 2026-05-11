package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.base.ISdmxVersion;
import io.sdmx.api.sdmx.model.beans.base.IURN;
import io.sdmx.api.sdmx.model.beans.base.IURNSingle;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptSchemeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.xs.IMaintainableRef;
import io.sdmx.api.sdmx.model.superbeans.base.MaintainableSuperBean;
import io.sdmx.api.sdmx.model.superbeans.conceptscheme.ConceptSchemeSuperBean;
import io.sdmx.api.sdmx.model.superbeans.datastructure.DataStructureSuperBean;
import io.sdmx.core.sdmx.manager.structure.SdmxSuperBeanRetrievalManagerImpl;
import io.sdmx.utils.core.object.ObjectUtil;

import java.util.Collection;
import java.util.Set;

/**
 * Workaround for an NPE in upstream {@code io.sdmx.utils.sdmx.structure.SuperBeanRefUtil#resolveReference}
 * (present in sdmx-core 2.3.21).
 *
 * <p>The upstream loop initialises {@code latestVersion = null} and then calls
 * {@code maintVersion.isLater(latestVersion)} on the first matching bean — which NPEs because
 * {@code SdmxVersion.compareTo(null)} dereferences {@code that.getMajor()}. The null guard on
 * line 53 checks {@code maintVersion}, but the variable that can actually be null on the first
 * iteration is {@code latestVersion}. The bug was dormant in earlier releases; sdmx-core 2.3.21
 * triggers it from {@code SdmxJsonSeriesDataWriterV2.startDataset} via
 * {@code DataStructureUtil.obtainNumericComponents}.
 *
 * <p>Only {@link #getConceptSchemeSuperBean} is reached on the current failing path; that method
 * (and {@link #getDataStructureSuperBean}, which sits on the same JSON-writer pipeline) is routed
 * through a corrected resolution loop. The other {@code get*SuperBean(IURNSingle)} methods on the
 * parent class are left untouched because their sibling set-returning getters are package-private,
 * so an override would require reflection or copy-pasting upstream internals — disproportionate for
 * code paths that are not currently exercised.
 *
 * <p>Remove this override once sdmx-core fixes the upstream defect.
 */
public class CustomSdmxSuperBeanRetrievalManagerImpl extends SdmxSuperBeanRetrievalManagerImpl {

    public CustomSdmxSuperBeanRetrievalManagerImpl(SdmxBeanRetrievalManager beanRetrievalManager) {
        super(beanRetrievalManager);
    }

    @Override
    public ConceptSchemeSuperBean getConceptSchemeSuperBean(IURNSingle<ConceptSchemeBean> ref) {
        Set<ConceptSchemeSuperBean> beans = getConceptSchemeSuperBeans((IURN<ConceptSchemeBean>) ref);
        return (ConceptSchemeSuperBean) safeResolveReference(beans, ref);
    }

    @Override
    public DataStructureSuperBean getDataStructureSuperBean(IURNSingle<DataStructureBean> ref) {
        Set<DataStructureSuperBean> beans = getDataStructureSuperBeans((IURN<DataStructureBean>) ref);
        return (DataStructureSuperBean) safeResolveReference(beans, ref);
    }

    private static MaintainableSuperBean safeResolveReference(Collection<? extends MaintainableSuperBean> maintainables, IMaintainableRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("Ref is null");
        }
        if (!ref.hasAgencyId()) {
            throw new IllegalArgumentException("Ref is missing AgencyId");
        }
        if (!ref.hasMaintainableId()) {
            throw new IllegalArgumentException("Ref is missing Id");
        }
        if (!ObjectUtil.validCollection(maintainables)) {
            return null;
        }

        MaintainableSuperBean latestVersionSb = null;
        ISdmxVersion latestVersion = null;
        for (MaintainableSuperBean currentMaintainable : maintainables) {
            if (!currentMaintainable.getAgencyId().equals(ref.getAgencyId())) {
                continue;
            }
            if (!currentMaintainable.getId().equals(ref.getMaintainableId())) {
                continue;
            }
            ISdmxVersion maintVersion = currentMaintainable.getBuiltFrom().getVersion();
            if (!ref.getVersion().isMatch(maintVersion)) {
                continue;
            }
            if (ref.getVersion().isAbsolute()) {
                return currentMaintainable;
            }
            if (latestVersion == null || (maintVersion != null && maintVersion.isLater(latestVersion))) {
                latestVersionSb = currentMaintainable;
                latestVersion = maintVersion;
            }
        }
        return latestVersionSb;
    }
}
