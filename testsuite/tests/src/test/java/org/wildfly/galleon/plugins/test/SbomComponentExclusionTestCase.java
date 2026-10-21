/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugins.test;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.parsers.JsonParser;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class SbomComponentExclusionTestCase {

    public static String installation;
    public static String included;
    public static String excluded;

    @BeforeClass
    public static void setUp() {
        installation = System.getProperty("server.sbom.install");
        included = System.getProperty("server.sbom.artifact.included");
        excluded = System.getProperty("server.sbom.artifact.excluded");
    }

    @Test
    public void checkSBOM() throws Exception {
        Path sbom = Paths.get(installation).resolve("sbom.cdx.json");
        final Bom bom = new JsonParser().parse(sbom.toFile());
        final Component excludedComponent = findComponent(bom, excluded);
        assertNull(excludedComponent);
        final Component includedComponent = findComponent(bom, included);
        assertNotNull(includedComponent);
    }

    private static Component findComponent(Bom bom, String name) {
        if (bom.getComponents() == null) {
            return null;
        }
        return bom.getComponents().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst().orElse(null);
    }
}
