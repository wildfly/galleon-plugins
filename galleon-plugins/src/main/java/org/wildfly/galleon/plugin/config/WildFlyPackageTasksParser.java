/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
