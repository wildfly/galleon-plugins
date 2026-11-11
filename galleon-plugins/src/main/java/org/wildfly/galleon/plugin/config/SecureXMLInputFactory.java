/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

public final class SecureXMLInputFactory {

    private SecureXMLInputFactory() {
    }

    public static XMLInputFactory newFactory() {
        XMLInputFactory xmlInputFactory = XMLInputFactory.newDefaultFactory();
        xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlInputFactory.setXMLResolver((publicId, systemId, baseURI, namespace) -> {
            throw new XMLStreamException("External references are not allowed");
        });
        return xmlInputFactory;
    }

}
