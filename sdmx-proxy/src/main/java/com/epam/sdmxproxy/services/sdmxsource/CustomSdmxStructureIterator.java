package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.collection.KeyValue;
import io.sdmx.api.exception.SdmxSemmanticException;
import io.sdmx.api.sdmx.constants.SDMX_STRUCTURE_TYPE;
import io.sdmx.api.sdmx.model.beans.base.AnnotationBean;
import io.sdmx.api.sdmx.model.beans.base.IDSDRelatedBean;
import io.sdmx.api.sdmx.model.beans.base.ILinkBean;
import io.sdmx.api.sdmx.model.beans.base.IURN;
import io.sdmx.api.sdmx.model.beans.base.IURNSingle;
import io.sdmx.api.sdmx.model.header.DatasetStructureReferenceBean;
import io.sdmx.core.sdmx.api.error.DataReaderExceptionHandler;
import io.sdmx.format.json.engine.data.reader.AbstractIterator;
import io.sdmx.format.json.engine.data.reader.AnnotationsIterator;
import io.sdmx.im.beans.base.LinkBean;
import io.sdmx.im.header.DatasetStructureReferenceBeanImpl;
import io.sdmx.utils.core.collection.KeyValueImpl;
import io.sdmx.utils.json.JsonReader;
import io.sdmx.utils.json.JsonReader.Iterator;
import io.sdmx.utils.sdmx.xs.URN;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomSdmxStructureIterator extends AbstractIterator {
    private List<ComponentIterator> componentIterators = new ArrayList<>();
    private LEVEL currentLevel = null;
    //	private String uri;
    private boolean inAttributes = false;
    private boolean inMeasures = false;
    private AnnotationsIterator annotationIterator;
    private List<ILinkBean> links = new ArrayList<>();

    public CustomSdmxStructureIterator(JsonReader jReader, DataReaderExceptionHandler exceptionHandler) {
        super(jReader, exceptionHandler);
    }

    public JsonDatasetStructuralMetadata getJsonDatasetStructuralMetadata() {
        return new JsonDatasetStructuralMetadata();
    }

    private List<AttraMapping> getList(LEVEL lvl) {
        List<AttraMapping> returnList = new ArrayList<>();
        for (ComponentIterator comp : componentIterators) {
            if (comp.getLevel() == lvl) {
                returnList.add(new AttraMapping(comp.componentMap, comp.id));
            }
        }
        return returnList;
    }

    @Override
    public Iterator start(String fieldName, boolean isObject) {

        if ("links".equals(fieldName)) {
            return new LinkIterator(jReader, exceptionHandler);
        }

        if ("dimensions".equals(fieldName)) {
            inAttributes = false;
            inMeasures = false;
            return this;
        }

        if ("attributes".equals(fieldName)) {
            inAttributes = true;
            inMeasures = false;
            return this;
        }

        if ("measures".equals(fieldName)) {
            inAttributes = false;
            inMeasures = true;
            return this;
        }

        if ("annotations".equals(fieldName)) {
            annotationIterator = new AnnotationsIterator(jReader, exceptionHandler);
            return annotationIterator;
        }


        if ("dataset".equalsIgnoreCase(fieldName)) {
            if (inAttributes) {
                currentLevel = LEVEL.DATASET_ATTR;
            } else {
                currentLevel = LEVEL.DATASET;
            }
        } else if ("series".equals(fieldName)) {
            if (inAttributes) {
                currentLevel = LEVEL.SERIES_ATTR;
            } else {
                currentLevel = LEVEL.SERIES;
            }
        } else if ("dimensionGroup".equals(fieldName)) {
            if (inAttributes) {
                currentLevel = LEVEL.DIMENSION_GROUP_ATTR;
            }
        } else if ("observation".equals(fieldName)) {
            if (inAttributes) {
                currentLevel = LEVEL.OBS_ATTR;
            } else if (inMeasures) {
                currentLevel = LEVEL.OBS_MEASURES;
            } else {
                currentLevel = LEVEL.OBS;
            }
        } else if (fieldName == null) {
            //We are in an array at the level defined by currentLevel
            ComponentIterator componentIterator = new ComponentIterator(jReader, currentLevel, exceptionHandler);
            componentIterators.add(componentIterator);
            return componentIterator;
        }
        return null;
    }

    @Override
    public void next(String fieldName) {
        //		if("uri".equals(fieldName)) {
        //			uri = jReader.getValueAsString();
        //		}
    }


    private enum LEVEL {
        DATASET,
        SERIES,
        OBS,
        DATASET_ATTR,
        SERIES_ATTR,
        OBS_ATTR,
        OBS_MEASURES,
        DIMENSION_GROUP_ATTR
    }

    public class JsonDatasetStructuralMetadata {
        private DatasetStructureReferenceBean datasetStructureReference;
        private List<AnnotationBean> annotationList;
        private List<AttraMapping> datasetAttributeList;
        private List<AttraMapping> seriesAttributeList;
        private List<AttraMapping> obsAttributeList;
        private List<AttraMapping> dimensionGroupAttributeList;
        private List<AttraMapping> seriesList;  //Series keys
        private List<String> obsIds;  //All the concepts at the observation level
        private List<String> measureIds = new ArrayList<>();  // multiple measure concepts
        private String dimensionAtObservation;

        public JsonDatasetStructuralMetadata() {
            annotationList = annotationIterator != null ? annotationIterator.getAnnotations() : Collections.EMPTY_LIST;
            datasetAttributeList = getList(LEVEL.DATASET_ATTR);
            seriesAttributeList = getList(LEVEL.SERIES_ATTR);
            obsAttributeList = getList(LEVEL.OBS_ATTR);
            dimensionGroupAttributeList = getList(LEVEL.DIMENSION_GROUP_ATTR);
            seriesList = getList(LEVEL.SERIES);

            // extract measure ids from component iterators
            componentIterators.forEach(i -> {
                if (i.getLevel() == LEVEL.OBS_MEASURES) {
                    measureIds.add(i.id);
                }
            });

            AttraMapping observationMap = null;
            if (seriesList.size() == 0) {
                //Flat - Observation Concept is last one in list
                seriesList = getList(LEVEL.OBS);
                observationMap = seriesList.get(seriesList.size() - 1);

            } else {
                ComponentIterator observationComp = null;
                for (ComponentIterator comp : componentIterators) {
                    if (comp.getLevel() == LEVEL.OBS) {
                        observationComp = comp;
                        break;
                    }
                }
                if (observationComp != null) {
                    observationMap = new AttraMapping(observationComp.componentMap, observationComp.id);
                }
            }
            if (observationMap == null) {
                throw new SdmxSemmanticException("Can not read JSON Data Message, missing Observation information in the Structure part of the message");
            }
            obsIds = new ArrayList<>();
            for (int i = 0; i < Integer.MAX_VALUE; i++) {
                KeyValue kv = observationMap.componentMap.get(i);
                if (kv == null) {
                    break;
                }
                dimensionAtObservation = kv.getConcept();
                obsIds.add(kv.getCode());
            }
            if (!links.isEmpty()) {
                EnumMap<SDMX_STRUCTURE_TYPE, IURNSingle<IDSDRelatedBean>> strucMap = new EnumMap<>(SDMX_STRUCTURE_TYPE.class);
                links.stream()
                        .forEach(link -> {
                            if (link.getUrn() != null) {
                                IURNSingle<IDSDRelatedBean> strucRef = URN.builder().urn(link.getUrn()).buildSingle(IDSDRelatedBean.class);
                                SDMX_STRUCTURE_TYPE targetReference = strucRef.getSdmxStructure();
                                strucMap.put(targetReference, strucRef);
                            }
                        });
                if (!strucMap.isEmpty()) {
                    IURNSingle<IDSDRelatedBean> paRef = strucMap.get(SDMX_STRUCTURE_TYPE.PROVISION_AGREEMENT);
                    IURNSingle<IDSDRelatedBean> dfRef = strucMap.get(SDMX_STRUCTURE_TYPE.DATAFLOW);
                    IURNSingle<IDSDRelatedBean> dsdRef = strucMap.get(SDMX_STRUCTURE_TYPE.DSD);

                    if (paRef != null) {
                        datasetStructureReference = new DatasetStructureReferenceBeanImpl(paRef.getMaintainableId(), paRef, null, null, dimensionAtObservation);
                    } else if (dfRef != null) {
                        datasetStructureReference = new DatasetStructureReferenceBeanImpl(dfRef.getMaintainableId(), dfRef, null, null, dimensionAtObservation);
                    } else if (dsdRef != null) {
                        datasetStructureReference = new DatasetStructureReferenceBeanImpl(dsdRef.getMaintainableId(), dsdRef, null, null, dimensionAtObservation);
                    }

                }
                //				ILink dsdLink = links.stream()
                //				.filter(link -> {
                //					String urn = link.getUrn();
                //					if(ObjectUtil.validString(urn)) {
                //						StructureReferenceBean strucRef = new StructureReferenceBeanImpl(urn);
                //						SDMX_STRUCTURE_TYPE targetReference = strucRef.getTargetReference();
                //						if(targetReference == SDMX_STRUCTURE_TYPE.DATAFLOW || targetReference == SDMX_STRUCTURE_TYPE.DSD || targetReference == SDMX_STRUCTURE_TYPE.PROVISION_AGREEMENT) {
                //							return true;
                //						}
                //					}
                //					return false;
                //				})
                //				.findAny()
                //				.orElse(null);
                //				if(dsdLink != null) {
                //					String urn = dsdLink.getUrn();
                //					StructureReferenceBean strucRef = new StructureReferenceBeanImpl(urn);
                //					datasetStructureReference = new DatasetStructureReferenceBeanImpl(strucRef.getMaintainableId(), strucRef, null, null, dimensionAtObservation);
                //				}
            }
            //			if(uri != null) {
            //				String[] uriSplit = uri.split("/");
            //				if(uriSplit.length > 4) {
            //					String version = uriSplit[uriSplit.length-1];
            //					String id = uriSplit[uriSplit.length-2];
            //					String agency = uriSplit[uriSplit.length-3];
            //					SDMX_STRUCTURE_TYPE structureType = SDMX_STRUCTURE_TYPE.parseClass(uriSplit[uriSplit.length-4]);
            //					StructureReferenceBean sRef = new StructureReferenceBeanImpl(agency, id, version, structureType);
            //					datasetStructureReference = new DatasetStructureReferenceBeanImpl(id, sRef, null, uri, dimensionAtObservation);
            //				}
            //			}
        }

        public DatasetStructureReferenceBean getDatasetStructureReference() {
            return datasetStructureReference;
        }

        public List<AnnotationBean> getAnnotationList() {
            return annotationList;
        }

        public List<AttraMapping> getDatasetAttributeList() {
            return datasetAttributeList;
        }

        public List<AttraMapping> getSeriesAttributeList() {
            return seriesAttributeList;
        }

        public List<AttraMapping> getObsAttributeList() {
            return obsAttributeList;
        }

        public List<AttraMapping> getDimensionGroupAttributeList() {
            return dimensionGroupAttributeList;
        }

        public List<AttraMapping> getSeriesList() {
            return seriesList;
        }

        public List<String> getObsIds() {
            return obsIds;
        }

        public String getDimensionAtObservation() {
            return dimensionAtObservation;
        }

        public List<String> getMeasureIds() {
            return measureIds;
        }
    }

    private class LinkIterator extends AbstractIterator {
        private String rel;
        private IURN urn;
        private URI uri;

        public LinkIterator(JsonReader jReader, DataReaderExceptionHandler exceptionHandler) {
            super(jReader, exceptionHandler);
        }

        @Override
        public void next(String fieldName) {
            String value = jReader.getValueAsString();
            switch (fieldName) {
                case "rel":
                    this.rel = value;
                    break;
                case "urn":
                    this.urn = URN.getInstance(value);
                    break;
                case "uri":
                    try {
                        this.uri = new URI(value);
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                    }
                    break;
            }
        }


        @Override
        public void end(String fieldName, boolean isObject) {
            if ("links".equals(fieldName) && isObject) {
                CustomSdmxStructureIterator.this.links.add(new LinkBean(rel, uri, null, urn, null, null));
            }
        }

    }

    public class AttraMapping {
        public Map<Integer, KeyValue> componentMap = new HashMap<>();
        public String id;

        public AttraMapping(Map<Integer, KeyValue> componentMap, String id) {
            this.componentMap = componentMap;
            this.id = id;
        }
    }

    private class ComponentIterator extends AbstractIterator {
        private LEVEL level;
        private String id;
        private Map<Integer, KeyValue> componentMap = new HashMap<>();

        private boolean inValues;
        private boolean coded;
        private int pos;

        public ComponentIterator(JsonReader jReader, LEVEL level, DataReaderExceptionHandler exceptionHandler) {
            super(jReader, exceptionHandler);
            this.level = level;
        }

        @Override
        public void next(String fieldName) {
            if (inValues) {
                if (fieldName == null) {
                    return;
                }
                switch (fieldName) {
                    case "id":
                        if (!coded && pos == 1) {
                            pos = 0;
                        }
                        coded = true;
                        componentMap.put(pos, KeyValueImpl.getInstance(this.id, jReader.getValueAsString()));
                        pos++;
                        break;
                    case "name":
                        if (!coded) {
                            //			            System.out.println("  MARKER #3 - name encountered");
                            componentMap.put(pos, KeyValueImpl.getInstance(this.id, jReader.getValueAsString()));
                            pos++;
                        }
                        break;
                    case "value":
                        componentMap.put(pos, KeyValueImpl.getInstance(this.id, jReader.getValueAsString()));
                        pos++;

                }
            } else if ("id".equals(fieldName)) {
                this.id = jReader.getValueAsString();
            }
        }

        @Override
        public Iterator start(String fieldName, boolean isObject) {
            //		    System.out.println("\nComponentIterator - START");
            if ("values".equals(fieldName)) {
                inValues = true;
            }
            return null;
        }

        public LEVEL getLevel() {
            return level;
        }
    }
}
