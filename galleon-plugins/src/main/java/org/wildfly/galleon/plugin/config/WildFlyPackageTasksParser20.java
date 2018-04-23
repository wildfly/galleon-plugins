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

import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.XmlNameProvider;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import static org.wildfly.galleon.plugin.config.WildFlyPackageTasksParser.NAMESPACE_2_0;

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

        COPY_ARTIFACTS(CopyArtifactsModelParser20.ELEMENT_LOCAL_NAME),
        COPY_PATHS(CopyPathsModelParser20.ELEMENT_LOCAL_NAME),
        DELETE_PATHS(DeletePathsModelParser20.ELEMENT_LOCAL_NAME),
        DIR("dir"),
        FILE_PERMISSIONS(FilePermissionsModelParser20.ELEMENT_LOCAL_NAME),
        FILTER(FileFilterModelParser20.ELEMENT_LOCAL_NAME),
        LINE_ENDINGS("line-endings"),
        MKDIRS("mkdirs"),
        TASKS("tasks"),
        UNIX("unix"),
        WINDOWS("windows"),
        XSL("xsl"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<QName, Element> elements;

        static {
            Map<QName, Element> elementsMap = new HashMap<QName, Element>();
            elementsMap.put(new QName(NAMESPACE_2_0, Element.COPY_ARTIFACTS.getLocalName()), Element.COPY_ARTIFACTS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.COPY_PATHS.getLocalName()), Element.COPY_PATHS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DELETE_PATHS.getLocalName()), Element.DELETE_PATHS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DIR.getLocalName()), Element.DIR);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILE_PERMISSIONS.getLocalName()), Element.FILE_PERMISSIONS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILTER.getLocalName()), Element.FILTER);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.LINE_ENDINGS.getLocalName()), Element.LINE_ENDINGS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.MKDIRS.getLocalName()), Element.MKDIRS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.TASKS.getLocalName()), Element.TASKS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.UNIX.getLocalName()), Element.UNIX);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.WINDOWS.getLocalName()), Element.WINDOWS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.XSL.getLocalName()), Element.XSL);
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

        NAME("name"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<QName, Attribute> attributes;

        static {
            Map<QName, Attribute> attributesMap = new HashMap<QName, Attribute>();
            attributesMap.put(new QName(NAME.getLocalName()), NAME);
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

    private final CopyArtifactsModelParser20 copyArtifactsModelParser;
    private final CopyPathsModelParser20 copyPathsModelParser;
    private final FileFilterModelParser20 fileFilterModelParser;
    private final FilePermissionsModelParser20 filePermissionsModelParser;
    private final DeletePathsModelParser20 deletePathsModelParser;
    private final XslTransformModelParser20 xslModelParser;

    WildFlyPackageTasksParser20() {
        this.fileFilterModelParser = new FileFilterModelParser20();
        this.copyArtifactsModelParser = new CopyArtifactsModelParser20(this.fileFilterModelParser);
        this.copyPathsModelParser = new CopyPathsModelParser20();
        this.filePermissionsModelParser = new FilePermissionsModelParser20(this.fileFilterModelParser);
        this.deletePathsModelParser = new DeletePathsModelParser20();
        this.xslModelParser = new XslTransformModelParser20();
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
                        case COPY_ARTIFACTS:
                            builder.addCopyArtifacts(copyArtifactsModelParser.parseCopyArtifacts(reader));
                            break;
                        case COPY_PATHS:
                            builder.addCopyPaths(copyPathsModelParser.parseCopyPaths(reader));
                            break;
                        case DELETE_PATHS:
                            builder.addDeletePaths(deletePathsModelParser.parseDeletePaths(reader));
                            break;
                        case XSL:
                            builder.addXslTransform(xslModelParser.parseCopyPaths(reader));
                            break;
                        case FILE_PERMISSIONS:
                            builder.addFilePermissions(filePermissionsModelParser.parseFilePermissions(reader));
                            break;
                        case MKDIRS:
                            parseMkdirs(reader, builder);
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

    private void parseMkdirs(final XMLStreamReader reader, final WildFlyPackageTasks.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DIR:
                            builder.addMkDirs(parseName(reader));
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
                            final FileFilter.Builder filterBuilder = FileFilter.builder();
                            fileFilterModelParser.parseFilter(reader, filterBuilder);
                            if(windows) {
                                builder.addWindowsLineEndFilter(filterBuilder.build());
                            } else {
                                builder.addUnixLineEndFilter(filterBuilder.build());
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
}
