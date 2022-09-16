/*
 * Copyright 2016-2022 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.wildfly.galleon.maven.ModuleParseResult.ModuleDependency;
import org.wildfly.galleon.plugin.WfConstants;

public class ModuleAliasTestCase {

    @Test
    public void testAlias() throws Exception {
        StringBuilder moduleAlias1Content = new StringBuilder();
        moduleAlias1Content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(System.lineSeparator());
        moduleAlias1Content.append("<module-alias xmlns=\"urn:jboss:module:1.9\" name=\"org.foo.alias1\" target-name=\"org.target\"/>");
        Map<ModuleIdentifier, Set<ModuleIdentifier>> map = new HashMap<>();
        Path moduleAlias1File = Paths.get("module1.xml");
        Files.write(moduleAlias1File, moduleAlias1Content.toString().getBytes());
        ModuleIdentifier alias1Id = new ModuleIdentifier("org.foo.alias1", "main");

        StringBuilder moduleAlias2Content = new StringBuilder();
        moduleAlias2Content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(System.lineSeparator());
        moduleAlias2Content.append("<module-alias xmlns=\"urn:jboss:module:1.9\" name=\"org.foo.alias1:2\" target-name=\"org.target\"/>");
        Path moduleAlias2File = Paths.get("module2.xml");
        Files.write(moduleAlias2File, moduleAlias2Content.toString().getBytes());
        ModuleIdentifier alias2Id = ModuleIdentifier.fromString("org.foo.alias1:2");

        StringBuilder moduleAlias3Content = new StringBuilder();
        moduleAlias3Content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(System.lineSeparator());
        moduleAlias3Content.append("<module-alias xmlns=\"urn:jboss:module:1.9\" name=\"org.foo.alias3\" target-name=\"org.target:2\"/>");
        Path moduleAlias3File = Paths.get("module3.xml");
        Files.write(moduleAlias3File, moduleAlias3Content.toString().getBytes());
        ModuleIdentifier alias3Id = new ModuleIdentifier("org.foo.alias3", "main");

        StringBuilder moduleTargetContent = new StringBuilder();
        moduleTargetContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(System.lineSeparator());
        moduleTargetContent.append("<module xmlns=\"urn:jboss:module:1.9\" name=\"org.target\"/>");
        Path moduleTargetContentFile = Paths.get("module-target.xml");
        Files.write(moduleTargetContentFile, moduleTargetContent.toString().getBytes());

        StringBuilder moduleTarget2Content = new StringBuilder();
        moduleTarget2Content.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(System.lineSeparator());
        moduleTarget2Content.append("<module xmlns=\"urn:jboss:module:1.9\" name=\"org.target:2\"/>");
        Path moduleTarget2ContentFile = Paths.get("module-target2.xml");
        Files.write(moduleTarget2ContentFile, moduleTarget2Content.toString().getBytes());

        try {
            ModuleXmlParser.populateAlias(moduleAlias1File, WfConstants.UTF8, map);
            ModuleXmlParser.populateAlias(moduleAlias2File, WfConstants.UTF8, map);
            ModuleXmlParser.populateAlias(moduleAlias3File, WfConstants.UTF8, map);

            ModuleParseResult res = ModuleXmlParser.parse(moduleTargetContentFile, WfConstants.UTF8, map);
            int numDependencies = 2;
            assertEquals(numDependencies, res.getDependencies().size());
            for (ModuleDependency dep : res.getDependencies()) {
                if (dep.getModuleId().equals(alias2Id) || dep.getModuleId().equals(alias1Id)) {
                    numDependencies -= 1;
                }
            }
            assertEquals(0, numDependencies);

            ModuleParseResult res2 = ModuleXmlParser.parse(moduleTarget2ContentFile, WfConstants.UTF8, map);
            numDependencies = 1;
            assertEquals(numDependencies, res2.getDependencies().size());
            for (ModuleDependency dep : res2.getDependencies()) {
                if (dep.getModuleId().equals(alias3Id)) {
                    numDependencies -= 1;
                }
            }
            assertEquals(0, numDependencies);

        } finally {
            Files.deleteIfExists(moduleAlias1File);
            Files.deleteIfExists(moduleAlias2File);
            Files.deleteIfExists(moduleAlias3File);
            Files.deleteIfExists(moduleTargetContentFile);
            Files.deleteIfExists(moduleTarget2ContentFile);
        }

    }
}
