/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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
