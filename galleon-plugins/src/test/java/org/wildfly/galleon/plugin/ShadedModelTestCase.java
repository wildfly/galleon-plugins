/*
 * Copyright 2016-2026 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.galleon.DefaultMessageWriter;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class ShadedModelTestCase {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void parseDependencyCoordsResolvesVersionsFromPropsWithoutResolving() throws Exception {
        final Map<String, String> versionProps = new HashMap<>();
        versionProps.put("org.foo:bar", "org.foo:bar:1.2.3");
        versionProps.put("org.baz:qux", "org.baz:qux:4.5.6");

        final ShadedModel model = shadedModel(
                "<shaded-model>"
                + "<name>test-shaded</name>"
                + "<shaded-dependencies>"
                + "<dependency>org.foo:bar:::jar</dependency>"
                + "<dependency>org.baz:qux:::jar</dependency>"
                + "</shaded-dependencies>"
                + "</shaded-model>", versionProps);

        final List<MavenArtifact> deps = model.parseDependencyCoords();

        assertEquals(2, deps.size());
        final Map<String, String> gaToVersion = new HashMap<>();
        for (MavenArtifact a : deps) {
            gaToVersion.put(a.getGroupId() + ":" + a.getArtifactId(), a.getVersion());
        }
        assertEquals("1.2.3", gaToVersion.get("org.foo:bar"));
        assertEquals("4.5.6", gaToVersion.get("org.baz:qux"));
    }

    @Test
    public void parseDependencyCoordsReturnsEmptyWhenNoShadedDependencies() throws Exception {
        final ShadedModel model = shadedModel(
                "<shaded-model><name>test-shaded</name></shaded-model>", new HashMap<>());

        assertEquals(List.of(), model.parseDependencyCoords());
    }

    @Test
    public void resolveDependencyCoordsResolvesVersionsWithoutInstalling() throws Exception {
        // Channel mode: no version in the props, so the version comes from the resolver.
        final ShadedModel model = shadedModel(
                "<shaded-model>"
                + "<name>test-shaded</name>"
                + "<shaded-dependencies>"
                + "<dependency>org.foo:bar:::jar</dependency>"
                + "</shaded-dependencies>"
                + "</shaded-model>",
                new HashMap<>(),
                true,
                artifacts -> {
                    for (MavenArtifact a : artifacts) {
                        a.setVersion("9.9.9");
                    }
                });

        final List<MavenArtifact> deps = model.resolveDependencyCoords();

        assertEquals(1, deps.size());
        assertEquals("org.foo", deps.get(0).getGroupId());
        assertEquals("bar", deps.get(0).getArtifactId());
        assertEquals("9.9.9", deps.get(0).getVersion());
    }

    private ShadedModel shadedModel(String xml, Map<String, String> versionProps) throws Exception {
        return shadedModel(xml, versionProps, false, artifacts -> {
            throw new AssertionError("parseDependencyCoords must not resolve artifacts");
        });
    }

    private ShadedModel shadedModel(String xml, Map<String, String> versionProps,
            boolean channelArtifactResolution, WfInstallPlugin.ArtifactGroupResolver resolver) throws Exception {
        final Path shadedModelFile = temp.newFile("shaded-model.xml").toPath();
        Files.write(shadedModelFile, xml.getBytes(StandardCharsets.UTF_8));
        final Path tmpPath = temp.newFolder().toPath();
        return new ShadedModel(
                false,
                shadedModelFile,
                tmpPath,
                resolver,
                new DefaultMessageWriter(),
                versionProps,
                a -> {
                    throw new AssertionError("SBOM-only paths must not install artifacts");
                },
                channelArtifactResolution,
                Optional.empty());
    }
}
