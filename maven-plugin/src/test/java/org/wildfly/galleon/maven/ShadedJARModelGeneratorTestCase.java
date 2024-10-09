/*
 * Copyright 2016-2024 Red Hat, Inc. and/or its affiliates
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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;

import org.apache.maven.project.MavenProject;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.IoUtils;
import org.junit.Assert;
import org.junit.Test;
import org.wildfly.galleon.plugin.ShadedModel;

public class ShadedJARModelGeneratorTestCase {

    @Test
    public void testGenerateDescriptor() throws Exception {
        Path target = Files.createTempDirectory("test-shaded");
        MavenProject project = new MavenProject();
        project.setGroupId("org.wildfly.galleon.test");
        project.setArtifactId("shaded-jar");
        project.setVersion("1.0.0.Final");
        project.getBuild().setOutputDirectory(target.toAbsolutePath().toString());

        Artifact shadedArtifact = new DefaultArtifact("org.wildfly.galleon.test", "shaded-jar", "1.0.0.Final", null, "jar", "", null);
        project.setArtifact(shadedArtifact);
        project.getArtifacts().add(new DefaultArtifact("org.wildfly.core", "wildfly-cli", "25.0.0.Final", null, "jar", "", null));
        project.getArtifacts().add(new DefaultArtifact("org.wildfly.core", "wildfly-controller-client", "25.0.0.Final", null, "jar", "", null));

        Map<String, String> manifestEntries = new HashMap<>();
        manifestEntries.put("k1", "val1");
        manifestEntries.put("k2", "val2");
        manifestEntries.put("k3", "val3");
        ShadedJARModelGeneratorMojo mojo = new ShadedJARModelGeneratorMojo();
        Field f1 = mojo.getClass().getDeclaredField("project");
        f1.setAccessible(true);
        f1.set(mojo, project);
        Field f2 = mojo.getClass().getDeclaredField("mainClass");
        f2.setAccessible(true);
        f2.set(mojo, "TestClass");
        Field f3 = mojo.getClass().getDeclaredField("manifestEntries");
        f3.setAccessible(true);
        f3.set(mojo, manifestEntries);

        Field f4 = mojo.getClass().getDeclaredField("projectBuildDir");
        f4.setAccessible(true);
        f4.set(mojo, target.toAbsolutePath().toString());
        mojo.execute();
        Path model = target.resolve("resources/packages/org.wildfly.galleon.test.shaded-jar.shaded/pm/wildfly/shaded/shaded-model.xml");
        Assert.assertTrue(Files.exists(model));
        Path pkg = target.resolve("resources/packages/org.wildfly.galleon.test.shaded-jar.shaded/package.xml");
        Assert.assertTrue(Files.exists(pkg));
        Map<String, String> mergedArtifacts = new HashMap<>();
        mergedArtifacts.put("org.wildfly.galleon.test:shaded-jar", "org.wildfly.galleon.test:shaded-jar:1.0::jar");
        mergedArtifacts.put("org.wildfly.core:wildfly-cli", "org.wildfly.core:wildfly-cli:25.0.0.Final:jar");
        mergedArtifacts.put("org.wildfly.core:wildfly-controller-client", "org.wildfly.core:wildfly-controller-client:25.0.0.Final::jar");
        ShadedModel shadedModel = new ShadedModel(false,
                model,
                target,
                (MavenArtifact artifact) -> {
                },
                new MessageWriter() {
            @Override
            public void verbose(Throwable cause, CharSequence message) {
            }

            @Override
            public void print(Throwable cause, CharSequence message) {
            }

            @Override
            public void error(Throwable cause, CharSequence message) {
            }

            @Override
            public boolean isVerboseEnabled() {
                return false;
            }

            @Override
            public void close() throws Exception {
            }
        }, mergedArtifacts, (MavenArtifact a) -> {
                    return null;
                },
                false,
                Optional.empty());
        Assert.assertEquals("TestClass", shadedModel.getMainClass());
        Assert.assertEquals(manifestEntries, shadedModel.getManifestEntries());
        List<MavenArtifact> lst = shadedModel.getArtifacts();
        Assert.assertEquals(3,lst.size());
        for(MavenArtifact a : lst) {
            Assert.assertTrue(mergedArtifacts.containsKey(a.getGroupId()+":"+a.getArtifactId()));
        }
        IoUtils.recursiveDelete(target);
    }
}
