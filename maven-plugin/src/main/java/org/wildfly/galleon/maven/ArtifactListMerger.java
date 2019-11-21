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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.Log;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 * Builder that also merges artifact List in the format checksum,artifact path
 *
 * @author jdenise@redhat.com
 */
public class ArtifactListMerger extends ArtifactListBuilder {

    public ArtifactListMerger(MavenArtifactRepositoryManager artifactResolver, Path localMvnRepoPath, Log log) {
        super(artifactResolver, localMvnRepoPath, log);
    }

    void addOffliner(ArtifactCoords coords) throws ProvisioningException, IOException {
        Path file = resolveArtifact(coords);
        List<String> lines = Files.readAllLines(file);
        for (String l : lines) {
            String[] split = l.split(",");
            String checksum = split[0];
            String path = split[1];
            getMap().put(path, checksum);
        }
    }
}
