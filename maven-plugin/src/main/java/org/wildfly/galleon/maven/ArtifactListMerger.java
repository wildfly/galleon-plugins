/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
