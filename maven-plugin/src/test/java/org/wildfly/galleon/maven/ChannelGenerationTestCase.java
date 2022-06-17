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

import static org.jboss.galleon.config.FeaturePackConfig.builder;
import static org.jboss.galleon.universe.FeaturePackLocation.fromString;
import static org.junit.Assert.assertEquals;
import static org.wildfly.galleon.maven.FeaturePackDependencySpec.create;
import static org.wildfly.galleon.plugin.ArtifactCoords.newGav;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;

public class ChannelGenerationTestCase {

    @Test
    public void testGeneratedChannel() throws IOException, NoSuchFieldException, IllegalAccessException {

        MavenProject project = new MavenProject();
        project.setGroupId("org.wildfly");
        project.setArtifactId("wildfly-galleon-pack");
        project.setVersion("27.0.0.Final");

        // build the org.wildfly.galleon-pack feature pack that depends on the wildfly-ee-galleon-pack
        WildFlyFeaturePackBuild fp = WildFlyFeaturePackBuild.builder()
                .addDependency(newGav("org.wildfly", "wildfly-ee-galleon-pack", "27.0.0.Final"),
                        create(builder(fromString("org.wildfly:wildfly-ee-galleon-pack:27.0.0.Final")).build()))
                .build();

        Dependency wildflyEEGalleonPackDep = new Dependency();
        wildflyEEGalleonPackDep.setGroupId("org.wildfly");
        wildflyEEGalleonPackDep.setArtifactId("wildfly-ee-galleon-pack");
        wildflyEEGalleonPackDep.setVersion("27.0.0.Final");
        wildflyEEGalleonPackDep.setType("zip");
        project.getDependencies().add(wildflyEEGalleonPackDep);

        Artifact wildflyEEGalleonArtifact = new DefaultArtifact( "org.wildfly",  "wildfly-ee-galleon-pack", "27.0.0.Final",  "provided", "zip", "", null);
        project.getArtifacts().add(wildflyEEGalleonArtifact);

        Artifact microprofileConfigAPIArtifact = new DefaultArtifact( "org.eclipse.microprofile.config",  "microprofile-config-api", "2.0",  "compile", "jar", "", null);
        project.getArtifacts().add(microprofileConfigAPIArtifact);

        WfFeaturePackBuildMojo mojo = new WfFeaturePackBuildMojo();
        Field f1 = mojo.getClass().getSuperclass().getDeclaredField("project");
        f1.setAccessible(true);
        f1.set(mojo, project);
        Field f2 = mojo.getClass().getSuperclass().getDeclaredField("addFeaturePacksAsRequiredChannels");
        f2.setAccessible(true);
        f2.set(mojo, Boolean.TRUE);

        String yaml = mojo.createYAMLChannel(fp);

        List<Channel> channels = ChannelMapper.fromString(yaml);
        assertEquals(1, channels.size());
        Channel channel = channels.get(0);

        assertEquals(1, channel.getChannelRequirements().size());
        assertChannelContainsRequirements(channel, "org.wildfly", "wildfly-ee-galleon-pack", "27.0.0.Final");

        assertEquals(2, channel.getStreams().size());
        assertChannelContainsStream(channel, "org.wildfly", "wildfly-galleon-pack", "27.0.0.Final");
        assertChannelContainsStream(channel, "org.eclipse.microprofile.config", "microprofile-config-api", "2.0");
    }

    private static void assertChannelContainsStream(Channel channel, String groupId, String artifactId, String version) {
        channel.getStreams().stream()
                .filter(s -> groupId.equals(s.getGroupId()) && artifactId.equals(s.getArtifactId()) && version.equals(s.getVersion()))
                .findFirst().orElseThrow();
    }

    private static void assertChannelContainsRequirements(Channel channel, String groupId, String artifactId, String version) {
        channel.getChannelRequirements().stream()
                .filter(s -> groupId.equals(s.getGroupId()) && artifactId.equals(s.getArtifactId()) && version.equals(s.getVersion()))
                .findFirst().orElseThrow();
    }
}
