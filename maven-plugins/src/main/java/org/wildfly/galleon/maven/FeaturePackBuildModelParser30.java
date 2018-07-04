/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ArtifactCoords.Gav;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.ConfigXml;
import org.jboss.galleon.xml.FeaturePackPackagesConfigParser10;
import org.jboss.galleon.xml.ProvisioningXmlParser10;
import org.jboss.galleon.xml.XmlNameProvider;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses the WildFly-based feature pack build config file (i.e. the config file that is
 * used to create a WildFly-based feature pack, not the config file inside the feature pack).
 *
 * @author Stuart Douglas
 * @author Eduardo Martins
 * @author Alexey Loubyansky
 */
class FeaturePackBuildModelParser30 implements XMLElementReader<WildFlyFeaturePackBuild.Builder> {

    public static final String NAMESPACE_3_0 = "urn:wildfly:feature-pack-build:3.0";

    enum Element {

        BUILD("build"),
        CONFIG("config"),
        DEFAULT_CONFIGS("default-configs"),
        DEFAULT_PACKAGES("default-packages"),
        DEPENDENCIES("dependencies"),
        DEPENDENCY("dependency"),
        GROUP("group"),
        NAME("name"),
        PACKAGE("package"),
        PACKAGES("packages"),
        PACKAGE_SCHEMAS("package-schemas"),
        PRODUCER("producer"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<QName, Element> elements;

        static {
            Map<QName, Element> elementsMap = new HashMap<QName, Element>(12);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.BUILD.getLocalName()), Element.BUILD);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.CONFIG.getLocalName()), Element.CONFIG);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.DEFAULT_CONFIGS.getLocalName()), Element.DEFAULT_CONFIGS);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.DEFAULT_PACKAGES.getLocalName()), Element.DEFAULT_PACKAGES);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.DEPENDENCIES.getLocalName()), Element.DEPENDENCIES);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.DEPENDENCY.getLocalName()), Element.DEPENDENCY);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.GROUP.getLocalName()), Element.GROUP);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.NAME.getLocalName()), Element.NAME);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.PACKAGE.getLocalName()), Element.PACKAGE);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.PACKAGES.getLocalName()), Element.PACKAGES);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.PACKAGE_SCHEMAS.getLocalName()), Element.PACKAGE_SCHEMAS);
            elementsMap.put(new QName(NAMESPACE_3_0, Element.PRODUCER.getLocalName()), Element.PRODUCER);
            elements = elementsMap;
        }

        static Element of(QName qName) {
            QName name;
            if (qName.getNamespaceURI().equals("")) {
                name = new QName(NAMESPACE_3_0, qName.getLocalPart());
            } else {
                name = qName;
            }
            final Element element = elements.get(name);
            return element == null ? UNKNOWN : element;
        }

        private final String name;

        Element(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        public String getLocalName() {
            return name;
        }
    }

    enum Attribute implements XmlNameProvider {

        ARTIFACT_ID("artifact-id"),
        GROUP_ID("group-id"),
        NAME("name"),
        PRODUCER("producer"),
        VERSION("version"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<QName, Attribute> attributes;

        static {
            Map<QName, Attribute> attributesMap = new HashMap<QName, Attribute>(5);
            attributesMap.put(new QName(ARTIFACT_ID.getLocalName()), ARTIFACT_ID);
            attributesMap.put(new QName(GROUP_ID.getLocalName()), GROUP_ID);
            attributesMap.put(new QName(NAME.getLocalName()), NAME);
            attributesMap.put(new QName(PRODUCER.getLocalName()), PRODUCER);
            attributesMap.put(new QName(VERSION.getLocalName()), VERSION);
            attributes = attributesMap;
        }

        static Attribute of(QName qName) {
            final Attribute attribute = attributes.get(qName);
            return attribute == null ? UNKNOWN : attribute;
        }

        private final String name;

        Attribute(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        @Override
        public String getLocalName() {
            return name;
        }

        @Override
        public String getNamespace() {
            return null;
        }
    }

    FeaturePackBuildModelParser30() {
    }

    @Override
    public void readElement(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {

        FeaturePackLocation fpl = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case PRODUCER:
                    fpl = FeaturePackLocation.fromString(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (fpl == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.PRODUCER));
        }
        builder.setProducer(fpl);

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());

                    switch (element) {
                        case DEPENDENCIES:
                            parseDependencies(reader, builder);
                            break;
                        case DEFAULT_PACKAGES:
                            parseDefaultPackages(reader, builder);
                            break;
                        case PACKAGE_SCHEMAS:
                            parsePackageSchemas(reader, builder);
                            break;
                        case CONFIG:
                            final ConfigModel.Builder config = ConfigModel.builder();
                            ConfigXml.readConfig(reader, config);
                            try {
                                builder.addConfig(config.build());
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException("Failed to create a config model instance", e);
                            }
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseDefaultPackages(final XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PACKAGE:
                            builder.addDefaultPackage(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseDependencies(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DEPENDENCY:
                            parseDependency(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseDependency(XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        FeaturePackLocation fpl = null;
        String groupId = null;
        String artifactId = null;
        String version = null;
        final int count = reader.getAttributeCount();
        final Set<Attribute> required = EnumSet.of(Attribute.GROUP_ID, Attribute.ARTIFACT_ID, Attribute.PRODUCER);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case GROUP_ID:
                    groupId = reader.getAttributeValue(i);
                    break;
                case ARTIFACT_ID:
                    artifactId = reader.getAttributeValue(i);
                    break;
                case PRODUCER:
                    fpl = FeaturePackLocation.fromString(reader.getAttributeValue(i));
                    break;
                case VERSION:
                    version = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        final Gav gav = ArtifactCoords.newGav(groupId, artifactId, version);
        String depName = null;
        final FeaturePackConfig.Builder depBuilder = FeaturePackConfig.builder(fpl);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    builder.addDependency(gav, FeaturePackDependencySpec.create(depName, depBuilder.build()));
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case NAME:
                            depName = reader.getElementText().trim();
                            break;
                        case DEFAULT_CONFIGS:
                            ProvisioningXmlParser10.parseDefaultConfigs(reader, depBuilder);
                            break;
                        case CONFIG:
                            final ConfigModel.Builder configBuilder = ConfigModel.builder();
                            ConfigXml.readConfig(reader, configBuilder);
                            try {
                                depBuilder.addConfig(configBuilder.build());
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException(e);
                            }
                            break;
                        case PACKAGES:
                            try {
                                FeaturePackPackagesConfigParser10.readPackages(reader, depBuilder);
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException(e);
                            }
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
    }

    private String parseName(final XMLStreamReader reader) throws XMLStreamException {
        final int count = reader.getAttributeCount();
        String name = null;
        final Set<Attribute> required = EnumSet.of(Attribute.NAME);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case NAME:
                    name = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        ParsingUtils.parseNoContent(reader);
        return name;
    }

    private void parsePackageSchemas(XMLStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case GROUP:
                            builder.addSchemaGroup(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }
}
