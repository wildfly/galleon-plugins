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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Alexey Loubyansky
 */
public class DeletePathsModelParser20 {

    public static final String ELEMENT_LOCAL_NAME = "delete-paths";

    enum Element {

        DELETE("delete"),
        // default unknown element
        UNKNOWN(null);

        private static final Map<String, Element> elements;

        static {
            elements = Collections.singletonMap(Element.DELETE.getLocalName(), Element.DELETE);
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

        PATH("path"),
        RECURSIVE("recursive"),
        // default unknown attribute
        UNKNOWN(null);

        private static final Map<String, Attribute> attributes;

        static {
            Map<String, Attribute> attributesMap = new HashMap<>(2);
            attributesMap.put(PATH.getLocalName(), PATH);
            attributesMap.put(RECURSIVE.getLocalName(), RECURSIVE);
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

    public List<DeletePath> parseDeletePaths(final XMLStreamReader reader) throws XMLStreamException {
        final List<DeletePath> list = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return list;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DELETE:
                            parseDeletePath(reader, list);
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

    private void parseDeletePath(XMLStreamReader reader, final List<DeletePath> list) throws XMLStreamException {
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
        list.add(new DeletePath(path, recursive));
        ParsingUtils.parseNoContent(reader);
    }
}
