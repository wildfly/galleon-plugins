/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

public final class SecureXMLInputFactories {

    private SecureXMLInputFactories() {
    }

    public static XMLInputFactory newFactory() {
        XMLInputFactory xmlInputFactory = XMLInputFactory.newDefaultFactory();
        xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        xmlInputFactory.setXMLResolver((publicID, systemID, baseURI, namespace) -> {
            throw new XMLStreamException("External references are not allowed");
        });
        return xmlInputFactory;
    }

}
