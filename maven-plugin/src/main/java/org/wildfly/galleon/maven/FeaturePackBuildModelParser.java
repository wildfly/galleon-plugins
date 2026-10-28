/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;


import java.io.InputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jboss.staxmapper.XMLMapper;

/**
 * @author Stuart Douglas
 */
public class FeaturePackBuildModelParser {

    private static final QName ROOT_3_0 = new QName(FeaturePackBuildModelParser30.NAMESPACE_3_0, FeaturePackBuildModelParser30.Element.BUILD.getLocalName());
    private static final QName ROOT_3_1 = new QName(FeaturePackBuildModelParser31.NAMESPACE, FeaturePackBuildModelParser31.Element.BUILD.getLocalName());
    private static final QName ROOT_3_2 = new QName(FeaturePackBuildModelParser32.NAMESPACE, FeaturePackBuildModelParser32.Element.BUILD.getLocalName());
    private static final QName ROOT_3_3 = new QName(FeaturePackBuildModelParser33.NAMESPACE, FeaturePackBuildModelParser33.Element.BUILD.getLocalName());
    private static final QName ROOT_3_4 = new QName(FeaturePackBuildModelParser34.NAMESPACE, FeaturePackBuildModelParser34.Element.BUILD.getLocalName());
    private static final QName ROOT_3_5 = new QName(FeaturePackBuildModelParser35.NAMESPACE, FeaturePackBuildModelParser35.Element.BUILD.getLocalName());

    private static final XMLInputFactory INPUT_FACTORY = XMLInputFactory.newInstance();

    private final XMLMapper mapper;

    public FeaturePackBuildModelParser() {
        mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(ROOT_3_0, new FeaturePackBuildModelParser30());
        mapper.registerRootElement(ROOT_3_1, new FeaturePackBuildModelParser31());
        mapper.registerRootElement(ROOT_3_2, new FeaturePackBuildModelParser32());
        mapper.registerRootElement(ROOT_3_3, new FeaturePackBuildModelParser33());
        mapper.registerRootElement(ROOT_3_4, new FeaturePackBuildModelParser34());
        mapper.registerRootElement(ROOT_3_5, new FeaturePackBuildModelParser35());
    }

    public WildFlyFeaturePackBuild parse(final InputStream input) throws XMLStreamException {

        final XMLInputFactory inputFactory = INPUT_FACTORY;
        setIfSupported(inputFactory, XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
        setIfSupported(inputFactory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        final XMLStreamReader streamReader = inputFactory.createXMLStreamReader(input);
        final WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        mapper.parseDocument(builder, streamReader);
        return builder.build();
    }

    private void setIfSupported(final XMLInputFactory inputFactory, final String property, final Object value) {
        if (inputFactory.isPropertySupported(property)) {
            inputFactory.setProperty(property, value);
        }
    }
}
