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


import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.XmlNameProvider;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Eduardo Martins
 * @author Alexey Loubyansky
 */
public class FilePermissionsModelParser20 {

    public static final String ELEMENT_LOCAL_NAME = "file-permissions";

    enum Element {

        // default unknown element
        UNKNOWN(null),
        PERMISSION("permission"),
        FILTER(FileFilterModelParser20.ELEMENT_LOCAL_NAME),
        ;

        private static final Map<String, Element> elements;

        static {
            Map<String, Element> elementsMap = new HashMap<>();
            elementsMap.put(Element.PERMISSION.getLocalName(), Element.PERMISSION);
            elementsMap.put(Element.FILTER.getLocalName(), Element.FILTER);
            elements = elementsMap;
        }

        static Element of(QName qName) {
            final Element element = elements.get(qName.getLocalPart());
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

        // default unknown attribute
        UNKNOWN(null),
        VALUE("value"),
        ;

        private static final Map<String, Attribute> attributes;

        static {
            Map<String, Attribute> attributesMap = new HashMap<>();
            attributesMap.put(VALUE.getLocalName(), VALUE);
            attributes = attributesMap;
        }

        static Attribute of(QName qName) {
            final Attribute attribute = attributes.get(qName.getLocalPart());
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

    private final FileFilterModelParser20 fileFilterModelParser;

    public FilePermissionsModelParser20() {
        this(new FileFilterModelParser20());
    }

    public FilePermissionsModelParser20(FileFilterModelParser20 fileFilterModelParser) {
        this.fileFilterModelParser = fileFilterModelParser;
    }

    public List<FilePermission> parseFilePermissions(final XMLStreamReader reader) throws XMLStreamException {
        List<FilePermission> list = new ArrayList<FilePermission>();
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return list;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PERMISSION:
                            list.add(parsePermission(reader));
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
        final FilePermission.Builder permissionBuilder = FilePermission.builder();
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
                    return permissionBuilder.build();
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            final FileFilter.Builder filterBuilder = FileFilter.builder();
                            fileFilterModelParser.parseFilter(reader, filterBuilder);
                            permissionBuilder.addFilter(filterBuilder.build());
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
