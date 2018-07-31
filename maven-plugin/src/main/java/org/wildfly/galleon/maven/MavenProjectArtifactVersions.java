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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.jboss.galleon.model.Gaec;
import org.jboss.galleon.model.Gaecv;

/**
 * Maps groupId:artifactId[::classifier] to groupId:artifactId[::classifier]:version
 *
 * @author Alexey Loubyansky
 */
class MavenProjectArtifactVersions {

    static MavenProjectArtifactVersions getInstance(MavenProject project) {
        return new MavenProjectArtifactVersions(project);
    }

    private final Map<Gaec, Gaecv> versions = new HashMap<>();

    private MavenProjectArtifactVersions(MavenProject project) {
        for (Artifact a : project.getArtifacts()) {
            final Gaec gaec = new Gaec(a.getGroupId(), a.getArtifactId(), a.getType(), a.getClassifier());
            final Gaecv gaecv = new Gaecv(gaec, a.getVersion());
            versions.put(gaec, gaecv);
        }
    }

    Gaecv getVersion(Gaec gaec) {
        return versions.get(gaec);
    }

    void store(Path target) throws IOException {
        Properties props = new Properties();
        for (Entry<Gaec, Gaecv> en : versions.entrySet()) {
            props.setProperty(en.getKey().toString(), en.getValue().toString());
        }
        try(OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE)) {
            props.store(out, null);
        }
    }
}
