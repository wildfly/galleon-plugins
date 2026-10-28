/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;


import java.io.InputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jboss.staxmapper.XMLMapper;
import org.wildfly.galleon.plugin.WildFlyPackageTasks;

/**
 * @author Alexey Loubyansky
 */
public class WildFlyPackageTasksParser {
    public static final String NAMESPACE_2_0 = "urn:wildfly:wildfly-feature-pack-tasks:2.0";
    public static final String NAMESPACE_3_0 = "urn:wildfly:wildfly-feature-pack-tasks:3.0";
    public static final String NAMESPACE_3_1 = "urn:wildfly:wildfly-feature-pack-tasks:3.1";
    public static final String NAMESPACE_3_2 = "urn:wildfly:wildfly-feature-pack-tasks:3.2";

    private static final QName ROOT_2_0 = new QName(NAMESPACE_2_0, WildFlyPackageTasksParser20.Element.TASKS.getLocalName());
    private static final QName ROOT_3_0 = new QName(NAMESPACE_3_0, WildFlyPackageTasksParser30.Element.TASKS.getLocalName());
    private static final QName ROOT_3_1 = new QName(NAMESPACE_3_1, WildFlyPackageTasksParser31.Element.TASKS.getLocalName());
    private static final QName ROOT_3_2 = new QName(NAMESPACE_3_2, WildFlyPackageTasksParser32.Element.TASKS.getLocalName());

    private static final XMLInputFactory INPUT_FACTORY = XMLInputFactory.newInstance();

    private final XMLMapper mapper;

    public WildFlyPackageTasksParser() {
        mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(ROOT_3_2, new WildFlyPackageTasksParser32());
        mapper.registerRootElement(ROOT_3_1, new WildFlyPackageTasksParser31());
        mapper.registerRootElement(ROOT_3_0, new WildFlyPackageTasksParser30());
        mapper.registerRootElement(ROOT_2_0, new WildFlyPackageTasksParser20());
    }

    public WildFlyPackageTasks parse(final InputStream input) throws XMLStreamException {

        final XMLInputFactory inputFactory = INPUT_FACTORY;
        setIfSupported(inputFactory, XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
        setIfSupported(inputFactory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        final XMLStreamReader streamReader = inputFactory.createXMLStreamReader(input);
        final WildFlyPackageTasks.Builder builder = WildFlyPackageTasks.builder();
        mapper.parseDocument(builder, streamReader);
        return builder.build();
    }

    private void setIfSupported(final XMLInputFactory inputFactory, final String property, final Object value) {
        if (inputFactory.isPropertySupported(property)) {
            inputFactory.setProperty(property, value);
        }
    }
}
