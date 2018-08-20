/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
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
import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

/**
 * Maps groupId:artifactId[::classifier] to groupId:artifactId[::classifier]:version
 *
 * @author Alexey Loubyansky
 */
class MavenProjectArtifactVersions {

    static MavenProjectArtifactVersions getInstance(MavenProject project) {
        return new MavenProjectArtifactVersions(project);
    }

    private final Map<String, String> versions = new TreeMap<String, String>();

    private MavenProjectArtifactVersions(MavenProject project) {
        for (Artifact artifact : project.getArtifacts()) {
            final StringBuilder buf = new StringBuilder(artifact.getGroupId()).append(':').
                    append(artifact.getArtifactId());
            final String classifier = artifact.getClassifier();
            final StringBuilder version = new StringBuilder(buf);
            if(classifier != null && !classifier.isEmpty()) {
                buf.append("::").append(classifier);
                version.append(':').append(artifact.getVersion()).append(':').append(classifier);
            } else {
                version.append(':').append(artifact.getVersion());
            }
            versions.put(buf.toString(), version.toString());
        }
    }

    String getVersion(String gac) {
        return versions.get(gac);
    }

    void remove(String groupId, String artifactId) {
        versions.remove(groupId + ':' + artifactId);
    }

    void store(Path target) throws IOException {
        try(BufferedWriter writer = Files.newBufferedWriter(target, StandardOpenOption.CREATE)) {
            for(Map.Entry<String, String> entry : versions.entrySet()) {
                writer.write(entry.getKey());
                writer.write('=');
                writer.write(entry.getValue());
                writer.newLine();
            }
        }
    }
}
