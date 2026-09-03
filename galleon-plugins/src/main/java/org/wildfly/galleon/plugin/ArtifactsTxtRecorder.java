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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.util.HashUtils;
import org.jboss.galleon.util.IoUtils;

/**
 * Records resolved artifacts into a text-based manifest file ({@value #ARTIFACT_LIST_FILE}).
 *
 * <p>Each line in the manifest follows the format:
 * {@code coordinates::sha256hash::relative/path/to/artifact.jar}
 * where coordinates use the standard Maven format
 * {@code groupId:artifactId:extension:version} (with optional classifier).</p>
 *
 * <p>Artifacts may be recorded by reference ({@link #record}) or copied into
 * a local cache directory first ({@link #cache}). When the same artifact is
 * recorded multiple times, the most recent target path wins. A cached artifact
 * whose path is superseded by a subsequent {@link #record} call will have its
 * cached copy deleted.</p>
 */
public class ArtifactsTxtRecorder implements ArtifactRecorder {

    /** Name of the artifact list file written by {@link #writeManifest()}. */
    public static final String ARTIFACT_LIST_FILE = "artifacts.txt";

    private static final String SEPARATOR = "::";

    private final Path stagedDir;
    private final Path cacheDir;
    private final Path artifactList;
    private final HashMap<String, Path> cachedArtifacts = new HashMap<>();

    /**
     * Creates a new recorder that writes artifacts relative to {@code stagedDir}.
     *
     * <p>If {@code cacheDir} is a relative path it is resolved against
     * {@code stagedDir}. The cache directory and an empty artifact list file
     * are created immediately.</p>
     *
     * @param stagedDir the root directory of the staged distribution
     * @param cacheDir  the directory used to cache copied artifact JARs
     * @throws IOException if the cache directory or artifact list cannot be created
     */
    public ArtifactsTxtRecorder(Path stagedDir, Path cacheDir) throws IOException {
        this.stagedDir = stagedDir;
        this.cacheDir = cacheDir.isAbsolute() ? cacheDir : stagedDir.resolve(cacheDir);
        this.artifactList = this.cacheDir.resolve(ARTIFACT_LIST_FILE);
        initCacheDir();
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the artifact was previously cached and the new target points to a
     * different file, the old cached copy is deleted.</p>
     */
    @Override
    public void record(MavenArtifact artifact, Path target) throws IOException {
        if (target == null) {
            return;
        }
        final String coord = artifact.getCoordsAsString();
        if (cachedArtifacts.containsKey(coord)) {
            final Path cachedPath = cachedArtifacts.get(coord);
            if (cachedPath.toAbsolutePath().startsWith(cacheDir) && !cachedPath.equals(target)) {
                Files.delete(cachedPath);
            }
        }
        cachedArtifacts.put(coord, target);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Copies the artifact JAR from {@code jarSrc} into the cache directory
     * using the artifact's canonical filename. If the artifact has already been
     * recorded (via {@link #record} or a prior {@link #cache} call), the copy
     * is skipped.</p>
     */
    @Override
    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        if (!cachedArtifacts.containsKey(artifact.getCoordsAsString())) {
            IoUtils.copy(jarSrc, cacheDir.resolve(artifact.getArtifactFileName()));
            record(artifact, cacheDir.resolve(artifact.getArtifactFileName()));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes all recorded artifacts to {@value #ARTIFACT_LIST_FILE} in the
     * cache directory. Each line contains the artifact coordinates, a SHA-256
     * hash of the artifact file, and the path relative to the staged directory,
     * separated by {@code ::}.</p>
     */
    @Override
    public void writeManifest() throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> entry : cachedArtifacts.entrySet()) {
            final String hash = HashUtils.hashFile(entry.getValue());
            final Path relativePath = stagedDir.relativize(entry.getValue());
            final String universalPath = relativePath.toString().replace(File.separatorChar, '/');
            sb.append(entry.getKey()).append(SEPARATOR).append(hash).append(SEPARATOR).append(universalPath).append("\n");
        }
        Files.writeString(artifactList, sb.toString());
    }

    private void initCacheDir() throws IOException {
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }
        Files.deleteIfExists(artifactList);
        Files.createFile(artifactList);
    }
}
