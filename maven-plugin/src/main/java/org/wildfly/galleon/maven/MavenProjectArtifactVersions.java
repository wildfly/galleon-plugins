/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.Utils;

/**
 * Maps groupId:artifactId[::classifier] to groupId:artifactId:version:[classifier]:type
 *
 * @author Alexey Loubyansky
 */
class MavenProjectArtifactVersions {

    static final String TEST_JAR = "test-jar";
    static final String SYSTEM = "system";

    static MavenProjectArtifactVersions getInstance(MavenProject project) {
        return new MavenProjectArtifactVersions(project);
    }

    private final Map<String, String> versions = new TreeMap<>();

    private MavenProjectArtifactVersions(MavenProject project) {
        for (Artifact artifact : project.getArtifacts()) {
            if (TEST_JAR.equals(artifact.getType()) || SYSTEM.equals(artifact.getScope())) {
                continue;
            }
            put(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), artifact.getVersion(), artifact.getType());
        }
    }

    static Set<Artifact> getFilteredArtifacts(MavenProject project, WildFlyFeaturePackBuild buildConfig) {
        Set<ArtifactCoords.Gav> dependencies = buildConfig.getDependencies().keySet();
        Set<ArtifactCoords.Ga> dependenciesGa = new HashSet<>();
        for (ArtifactCoords.Gav gav : dependencies) {
            dependenciesGa.add(gav.toGa());
        }
        Set<Artifact> ret = new HashSet<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (TEST_JAR.equals(artifact.getType()) || SYSTEM.equals(artifact.getScope())) {
                continue;
            }
            if (!dependenciesGa.contains(ArtifactCoords.newGa(artifact.getGroupId(), artifact.getArtifactId()))) {
                ret.add(artifact);
            }
        }
        return ret;
    }

    String getVersion(String gac) {
        return versions.get(gac);
    }

    Map<String, String> getArtifacts() {
        return Collections.unmodifiableMap(versions);
    }

    private void put(final String groupId, final String artifactId, final String classifier, final String version, final String type) {
        final StringBuilder buf = new StringBuilder(groupId).append(':').
                append(artifactId);
        final StringBuilder versionClassifier = new StringBuilder(buf);
        versionClassifier.append(':').append(version).append(':');
        if(classifier != null && !classifier.isEmpty()) {
            buf.append("::").append(classifier);
            versionClassifier.append(classifier);
        }
        versions.put(buf.toString(), versionClassifier.append(':').append(type).toString());
    }

    void remove(String groupId, String artifactId) {
        versions.remove(groupId + ':' + artifactId);
    }

    void store(Path target) throws IOException, ProvisioningException {
        store(versions, target);
    }

    static void store(Map<String, String> map, Path target) throws IOException, ProvisioningException {
        Map<String, String> existingProperties = new HashMap<>();
        if (Files.exists(target)) {
           // Read its content and only add entries that are not present.
           existingProperties = Utils.readProperties(target);
        }
        // We could have an existing file, append to it.
        try(BufferedWriter writer = Files.newBufferedWriter(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for(Map.Entry<String, String> entry : map.entrySet()) {
                if (!existingProperties.containsKey(entry.getKey())) {
                    writer.write(entry.getKey());
                    writer.write('=');
                    writer.write(entry.getValue());
                    writer.newLine();
                }
            }
        }
    }
}
