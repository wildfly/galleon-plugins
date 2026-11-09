/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public final class SecureDocumentBuilderFactory {

    private SecureDocumentBuilderFactory() {
    }

    public static DocumentBuilderFactory newInstance() {
        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return dbf;
    }

}
