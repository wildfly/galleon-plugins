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
public class XslTransformModelParser20 {

    public static final String ELEMENT_LOCAL_NAME = "xsl";

    enum Element {

        PARAM("param"),
        PARAMS("params"),
        TRANSFORM("transform"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<String, Element> elements;

        static {
            elements = new HashMap<>(3);
            elements.put(Element.PARAM.getLocalName(), Element.PARAM);
            elements.put(Element.PARAMS.getLocalName(), Element.PARAMS);
            elements.put(Element.TRANSFORM.getLocalName(), Element.TRANSFORM);
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

        NAME("name"),
        OUTPUT("output"),
        SRC("src"),
        STYLESHEET("stylesheet"),
        VALUE("value"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<String, Attribute> attributes;

        static {
            Map<String, Attribute> attributesMap = new HashMap<>(5);
            attributesMap.put(NAME.getLocalName(), NAME);
            attributesMap.put(OUTPUT.getLocalName(), OUTPUT);
            attributesMap.put(SRC.getLocalName(), SRC);
            attributesMap.put(STYLESHEET.getLocalName(), STYLESHEET);
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

    public XslTransformModelParser20() {
    }

    public List<XslTransform> parseCopyPaths(final XMLStreamReader reader) throws XMLStreamException {
        final List<XslTransform> list = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return list;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case TRANSFORM:
                            parseTransform(reader, list);
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

    private void parseTransform(XMLStreamReader reader, final List<XslTransform> list) throws XMLStreamException {
        final XslTransform.Builder builder = XslTransform.builder();
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case SRC:
                    builder.setSrc(reader.getAttributeValue(i));
                    break;
                case OUTPUT:
                    builder.setOutput(reader.getAttributeValue(i));
                    break;
                case STYLESHEET:
                    builder.setStylesheet(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    final XslTransform result = builder.build();
                    if (result.getSrc() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.SRC));
                    }
                    if (result.getOutput() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.OUTPUT));
                    }
                    if (result.getStylesheet() == null) {
                        throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.STYLESHEET));
                    }
                    list.add(result);
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PARAMS:
                            parseParams(reader, builder);
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

    private void parseParams(XMLStreamReader reader, XslTransform.Builder result) throws XMLStreamException {
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

    private void parseParam(XMLStreamReader reader, XslTransform.Builder result) throws XMLStreamException {
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
}
