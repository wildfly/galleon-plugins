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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ArtifactRecorder {
    protected static final String ARTIFACT_LIST_FILE = "artifacts.txt";
    private static final String SEPARATOR = "::";
    private final Path stagedDir;
    private final Path cacheDir;
    private final Path artifactList;
    private final HashMap<String, Path> cachedArtifacts = new HashMap<>();

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

    /**
     * adds the artifact's coordinates to the artifacts list.
     *
     * If the artifact is recorded twice, the most recent {@code target} is used. If the artifact was recorded with a jar
     * cached in {@code cacheDir} and is recorded again pointing to the external jar, the external jar will be used and
     * cached copy will be removed.
     *
     * @param artifact
     * @param target
     * @throws IOException
     */
    public void record(MavenArtifact artifact, Path target) throws IOException {
        final String coord = artifact.getCoordsAsString();
        if (cachedArtifacts.containsKey(coord)) {
            // if the artifact file was cached and the new target points to a different file, remove the old cached file
            final Path cachedPath = cachedArtifacts.get(coord);
            if (cachedPath.toAbsolutePath().startsWith(cacheDir) && !cachedPath.equals(target)) {
                Files.delete(cachedPath);
            }
        }
        cachedArtifacts.put(coord, target);
    }

    /**
     * save a copy of {@code jarSrc} in the {@code cacheDir} and record it using {@link ArtifactRecorder#record(MavenArtifact, Path)}
     *
     * @param artifact
     * @param jarSrc
     * @throws MavenUniverseException
     * @throws IOException
     */
    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        IoUtils.copy(jarSrc, cacheDir.resolve(artifact.getArtifactFileName()));

        record(artifact, cacheDir.resolve(artifact.getArtifactFileName()));
    }

    /**
     * persist list of recorded artifacts in cacheDir/{@value ArtifactRecorder#ARTIFACT_LIST_FILE}
     * @throws IOException
     */
    public void writeCacheManifest() throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> entry : cachedArtifacts.entrySet()) {
            final String hash = HashUtils.hashFile(entry.getValue());
            final Path relativePath = stagedDir.relativize(entry.getValue());
            final String universalPath = relativePath.toString().replace(File.separatorChar, '/');
            sb.append(entry.getKey()).append(SEPARATOR).append(hash).append(SEPARATOR).append(universalPath).append("\n");
        }
        Files.writeString(artifactList, sb.toString());
    }
}
