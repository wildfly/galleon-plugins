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
package org.wildfly.galleon.plugin;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.util.HashUtils;
import org.jboss.galleon.util.IoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ArtifactRecorder {
    private static final String ARTIFACT_LIST_FILE = "artifacts.txt";
    private static final String SEPARATOR = "::";
    private final Path stagedDir;
    private final Path cacheDir;
    private final Path artifactList;

    public ArtifactRecorder(Path stagedDir, Path cacheDir) throws IOException {
        this.stagedDir = stagedDir;

        if (cacheDir.isAbsolute()) {
            this.cacheDir = cacheDir;
        } else {
            this.cacheDir = stagedDir.resolve(cacheDir);
        }

        this.artifactList = this.cacheDir.resolve(ARTIFACT_LIST_FILE);

        initCacheDir();
    }

    private void initCacheDir() throws IOException {
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }

        Files.deleteIfExists(artifactList);
        Files.createFile(artifactList);
    }

    public void record(MavenArtifact artifact, Path target) throws IOException {
        final String hash = HashUtils.hashFile(artifact.getPath());
        Files.writeString(artifactList,
                artifact.getCoordsAsString() + SEPARATOR + hash + SEPARATOR + stagedDir.relativize(target) + System.lineSeparator(),
                StandardOpenOption.APPEND);
    }

    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        IoUtils.copy(jarSrc, cacheDir.resolve(artifact.getArtifactFileName()));

        record(artifact, cacheDir.resolve(artifact.getArtifactFileName()));
    }
}
