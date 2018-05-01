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
package org.wildfly.galleon.plugin.config;

import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.ConfigXml;
import org.jboss.galleon.xml.XmlNameProvider;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.wildfly.galleon.plugin.WildFlyPackageTasks;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import static org.wildfly.galleon.plugin.config.WildFlyPackageTasksParser.NAMESPACE_2_0;

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
class WildFlyPackageTasksParser20 implements XMLElementReader<WildFlyPackageTasks.Builder> {

    enum Element {

        CONFIG("config"),
        COPY_ARTIFACT("copy-artifact"),
        COPY_PATH("copy-path"),
        DELETE("delete"),
        EXAMPLE_CONFIGS("example-configs"),
        FILE_PERMISSIONS("file-permissions"),
        FILTER("filter"),
        LINE_ENDINGS("line-endings"),
        MKDIR("mkdir"),
        PARAM("param"),
        PARAMS("params"),
        PERMISSION("permission"),
        TASKS("tasks"),
        TRANSFORM("transform"),
        UNIX("unix"),
        WINDOWS("windows"),
        XML_MERGE("xml-merge"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<QName, Element> elements;

        static {
            Map<QName, Element> elementsMap = new HashMap<QName, Element>();
            elementsMap.put(new QName(NAMESPACE_2_0, Element.CONFIG.getLocalName()), Element.CONFIG);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.COPY_ARTIFACT.getLocalName()), Element.COPY_ARTIFACT);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.COPY_PATH.getLocalName()), Element.COPY_PATH);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DELETE.getLocalName()), Element.DELETE);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.EXAMPLE_CONFIGS.getLocalName()), Element.EXAMPLE_CONFIGS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILE_PERMISSIONS.getLocalName()), Element.FILE_PERMISSIONS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILTER.getLocalName()), Element.FILTER);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.LINE_ENDINGS.getLocalName()), Element.LINE_ENDINGS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.MKDIR.getLocalName()), Element.MKDIR);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.PARAM.getLocalName()), Element.PARAM);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.PARAMS.getLocalName()), Element.PARAMS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.PERMISSION.getLocalName()), Element.PERMISSION);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.TASKS.getLocalName()), Element.TASKS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.TRANSFORM.getLocalName()), Element.TRANSFORM);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.UNIX.getLocalName()), Element.UNIX);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.WINDOWS.getLocalName()), Element.WINDOWS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.XML_MERGE.getLocalName()), Element.XML_MERGE);
            elements = elementsMap;
        }

        static Element of(QName qName) {
            QName name;
            if (qName.getNamespaceURI().equals("")) {
                name = new QName(NAMESPACE_2_0, qName.getLocalPart());
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

        ARTIFACT("artifact"),
        BASEDIR("basedir"),
        EXTRACT("extract"),
        GROUP("group"),
        INCLUDE("include"),
        MODEL("model"),
        NAME("name"),
        ORIGIN("origin"),
        OUTPUT("output"),
        PATH("path"),
        PATTERN("pattern"),
        PHASE("phase"),
        RELATIVE_TO("relative-to"),
        RECURSIVE("recursive"),
        REPLACE_PROPERTIES("replace-props"),
        SRC("src"),
        STYLESHEET("stylesheet"),
        TARGET("target"),
        TO_LOCATION("to-location"),
        VALUE("value"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<QName, Attribute> attributes;

        static {
            Map<QName, Attribute> attributesMap = new HashMap<QName, Attribute>();
            attributesMap.put(new QName(ARTIFACT.getLocalName()), ARTIFACT);
            attributesMap.put(new QName(BASEDIR.getLocalName()), BASEDIR);
            attributesMap.put(new QName(EXTRACT.getLocalName()), EXTRACT);
            attributesMap.put(new QName(GROUP.getLocalName()), GROUP);
            attributesMap.put(new QName(INCLUDE.getLocalName()), INCLUDE);
            attributesMap.put(new QName(MODEL.getLocalName()), MODEL);
            attributesMap.put(new QName(NAME.getLocalName()), NAME);
            attributesMap.put(new QName(ORIGIN.getLocalName()), ORIGIN);
            attributesMap.put(new QName(OUTPUT.getLocalName()), OUTPUT);
            attributesMap.put(new QName(PATH.getLocalName()), PATH);
            attributesMap.put(new QName(PATTERN.getLocalName()), PATTERN);
            attributesMap.put(new QName(PHASE.getLocalName()), PHASE);
            attributesMap.put(new QName(RECURSIVE.getLocalName()), RECURSIVE);
            attributesMap.put(new QName(RELATIVE_TO.getLocalName()), RELATIVE_TO);
            attributesMap.put(new QName(REPLACE_PROPERTIES.getLocalName()), REPLACE_PROPERTIES);
            attributesMap.put(new QName(SRC.getLocalName()), SRC);
            attributesMap.put(new QName(STYLESHEET.getLocalName()), STYLESHEET);
            attributesMap.put(new QName(TARGET.getLocalName()), TARGET);
            attributesMap.put(new QName(TO_LOCATION.getLocalName()), TO_LOCATION);
            attributesMap.put(new QName(VALUE.getLocalName()), VALUE);
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

    @Override
    public void readElement(final XMLExtendedStreamReader reader, final WildFlyPackageTasks.Builder builder) throws XMLStreamException {

        final Set<Attribute> required = EnumSet.noneOf(Attribute.class);
        final int count = reader.getAttributeCount();
        if (count != 0) {
            throw ParsingUtils.unexpectedContent(reader);
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());

                    switch (element) {
                        case COPY_ARTIFACT:
                            builder.addTask(parseCopyArtifact(reader));
                            break;
                        case COPY_PATH:
                            builder.addTask(parseCopyPath(reader));
                            break;
                        case DELETE:
                            builder.addTask(parseDeletePath(reader));
                            break;
                        case EXAMPLE_CONFIGS:
                            builder.addTask(parseExampleConfigs(reader));
                            break;
                        case TRANSFORM:
                            builder.addTask(parseTransform(reader));
                            break;
                        case FILE_PERMISSIONS:
                            parseFilePermissions(reader, builder);
                            break;
                        case MKDIR:
                            builder.addMkDir(parseMkdir(reader));
                            break;
                        case XML_MERGE:
                            builder.addTask(parseXmlMerge(reader));
                            break;
                        case LINE_ENDINGS:
                            parseLineEndings(reader, builder);
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

    private ExampleFpConfigs parseExampleConfigs(final XMLExtendedStreamReader reader) throws XMLStreamException {
        final ExampleFpConfigs exampleConfigs = new ExampleFpConfigs();
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case ORIGIN:
                    exampleConfigs.setOrigin(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return exampleConfigs;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case CONFIG:
                            parseExampleConfigs(reader, exampleConfigs);
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

    private void parseExampleConfigs(final XMLExtendedStreamReader reader, ExampleFpConfigs builder) throws XMLStreamException {
        final ConfigModel.Builder configBuilder = ConfigModel.builder();
        ConfigXml.readConfig(reader, configBuilder);
        try {
            builder.addConfig(configBuilder.build());
        } catch (ProvisioningDescriptionException e) {
            throw new XMLStreamException("Failed to instantiate config model", e);
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

    private String parseMkdir(final XMLStreamReader reader) throws XMLStreamException {
        return parseName(reader);
    }

    private void parseLineEndings(final XMLStreamReader reader, final WildFlyPackageTasks.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case WINDOWS:
                            parseLineEnding(reader, builder, true);
                            break;
                        case UNIX:
                            parseLineEnding(reader, builder, true);
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

    private void parseLineEnding(XMLStreamReader reader, final WildFlyPackageTasks.Builder builder, boolean windows) throws XMLStreamException {
        if(reader.getAttributeCount() != 0) {
            throw ParsingUtils.unexpectedContent(reader);
        }

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            if(windows) {
                                builder.addWindowsLineEndFilter(parseFilter(reader));
                            } else {
                                builder.addUnixLineEndFilter(parseFilter(reader));
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

    public FileFilter parseFilter(XMLStreamReader reader) throws XMLStreamException {
        final FileFilter builder = new FileFilter();
        final Set<Attribute> required = EnumSet.of(Attribute.PATTERN, Attribute.INCLUDE);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case PATTERN:
                    builder.setPatternString(reader.getAttributeValue(i));
                    break;
                case INCLUDE:
                    if(Boolean.parseBoolean(reader.getAttributeValue(i))) {
                        builder.setInclude();
                    }
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        ParsingUtils.parseNoContent(reader);
        return builder;
    }

    private CopyArtifact parseCopyArtifact(XMLStreamReader reader) throws XMLStreamException {
        final CopyArtifact builder = new CopyArtifact();
        final Set<Attribute> required = EnumSet.of(Attribute.ARTIFACT, Attribute.TO_LOCATION);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case ARTIFACT:
                    builder.setArtifact(reader.getAttributeValue(i));
                    break;
                case TO_LOCATION:
                    builder.setToLocation(reader.getAttributeValue(i));
                    break;
                case EXTRACT:
                    if(Boolean.parseBoolean(reader.getAttributeValue(i))) {
                        builder.setExtract();
                    }
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return builder;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            builder.addFilter(parseFilter(reader));
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

    private CopyPath parseCopyPath(XMLExtendedStreamReader reader) throws XMLStreamException {
        final CopyPath cpBuilder = new CopyPath();
        boolean src = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case SRC:
                    cpBuilder.setSrc(reader.getAttributeValue(i));
                    src = true;
                    break;
                case TARGET:
                    cpBuilder.setTarget(reader.getAttributeValue(i));
                    break;
                case REPLACE_PROPERTIES:
                    cpBuilder.setReplaceProperties(Boolean.parseBoolean(reader.getAttributeValue(i)));
                    break;
                case RELATIVE_TO:
                    cpBuilder.setRelativeTo(reader.getAttributeValue(i));
                    src = true;
                    break;
                default:
                    throw ParsingUtils.unexpectedAttribute(reader, i);
            }
        }
        if (!src) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.PATH));
        }
        ParsingUtils.parseNoContent(reader);
        return cpBuilder;
    }

    private void parseFilePermissions(final XMLStreamReader reader, WildFlyPackageTasks.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PERMISSION:
                            builder.addFilePermissions(parsePermission(reader));
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

    protected FilePermission parsePermission(XMLStreamReader reader) throws XMLStreamException {
        final FilePermission permissionBuilder = new FilePermission();
        final Set<Attribute> required = EnumSet.of(Attribute.VALUE);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case VALUE:
                    permissionBuilder.setValue(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return permissionBuilder;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            permissionBuilder.addFilter(parseFilter(reader));
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

    private DeletePath parseDeletePath(XMLStreamReader reader) throws XMLStreamException {
        final int count = reader.getAttributeCount();
        String path = null;
        boolean recursive = false;
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case PATH:
                    path = reader.getAttributeValue(i);
                    break;
                case RECURSIVE:
                    if(Boolean.parseBoolean(reader.getAttributeValue(i))) {
                        recursive = true;
                    }
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (path == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.PATH));
        }
        ParsingUtils.parseNoContent(reader);
        return new DeletePath(path, recursive);
    }

    private XslTransform parseTransform(XMLExtendedStreamReader reader) throws XMLStreamException {
        final XslTransform result = new XslTransform();
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case SRC:
                    result.setSrc(reader.getAttributeValue(i));
                    break;
                case OUTPUT:
                    result.setOutput(reader.getAttributeValue(i));
                    break;
                case STYLESHEET:
                    result.setStylesheet(reader.getAttributeValue(i));
                    break;
                case PHASE:
                    result.setPhase(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedAttribute(reader, i);
            }
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    if (result.getSrc() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.SRC));
                    }
                    if (result.getOutput() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.OUTPUT));
                    }
                    if (result.getStylesheet() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.STYLESHEET));
                    }
                    return result;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PARAMS:
                            parseParams(reader, result);
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

    private void parseParams(XMLStreamReader reader, XslTransform result) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PARAM:
                            parseParam(reader, result);
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

    private void parseParam(XMLStreamReader reader, XslTransform result) throws XMLStreamException {
        String name = null;
        String value = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case NAME:
                    name = reader.getAttributeValue(i);
                    break;
                case VALUE:
                    value = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if(name == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.NAME));
        }
        if(value == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.VALUE));
        }
        ParsingUtils.parseNoContent(reader);
        result.setParam(name, value);
    }

    private XmlMerge parseXmlMerge(XMLStreamReader reader) throws XMLStreamException {
        final XmlMerge builder = new XmlMerge();
        final Set<Attribute> required = EnumSet.of(Attribute.OUTPUT);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case OUTPUT:
                    builder.setOutput(reader.getAttributeValue(i));
                    break;
                case BASEDIR:
                    builder.setBasedir(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return builder;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            builder.addFilter(parseFilter(reader));
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
