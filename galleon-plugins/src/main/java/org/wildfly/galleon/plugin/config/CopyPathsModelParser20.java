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
public class CopyPathsModelParser20 {

    public static final String ELEMENT_LOCAL_NAME = "copy-paths";

    enum Element {

        PATH("path"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<String, Element> elements;

        static {
            elements = Collections.singletonMap(Element.PATH.getLocalName(), Element.PATH);
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

        SRC("src"),
        TARGET("target"),
        REPLACE_PROPERTIES("replace-props"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<String, Attribute> attributes;

        static {
            Map<String, Attribute> attributesMap = new HashMap<>();
            attributesMap.put(SRC.getLocalName(), SRC);
            attributesMap.put(TARGET.getLocalName(), TARGET);
            attributesMap.put(REPLACE_PROPERTIES.getLocalName(), REPLACE_PROPERTIES);
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

    public CopyPathsModelParser20() {
    }

    public List<CopyPath> parseCopyPaths(final XMLStreamReader reader) throws XMLStreamException {
        final List<CopyPath> list = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return list;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PATH:
                            parseCopyPath(reader, list);
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

    private void parseCopyPath(XMLStreamReader reader, final List<CopyPath> list) throws XMLStreamException {
        final CopyPath.Builder builder = CopyPath.builder();
        boolean src = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case SRC:
                    builder.setSrc(reader.getAttributeValue(i));
                    src = true;
                    break;
                case TARGET:
                    builder.setTarget(reader.getAttributeValue(i));
                    break;
                case REPLACE_PROPERTIES:
                    builder.setReplaceProperties(Boolean.parseBoolean(reader.getAttributeValue(i)));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!src) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.SRC));
        }
        ParsingUtils.parseNoContent(reader);
        list.add(builder.build());
    }
}
