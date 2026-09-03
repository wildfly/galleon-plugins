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

import java.io.IOException;
import java.nio.file.Path;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * Records artifacts resolved and installed during Galleon provisioning.
 *
 * <p>Implementations collect artifact metadata as provisioning proceeds and
 * persist a manifest when provisioning completes. The two recording methods
 * ({@link #record} and {@link #cache}) are called by the install plugin and
 * artifact installers each time an artifact is placed into the distribution.
 * {@link #writeManifest()} is called once at the end of provisioning to
 * flush all collected data to persistent storage.</p>
 *
 * @see ArtifactsTxtRecorder
 * @see SbomArtifactRecorder
 * @see ChainedArtifactRecorder
 */
public interface ArtifactRecorder {

    /**
     * Records an artifact that has been installed at the given target location.
     *
     * <p>If the same artifact is recorded more than once, the most recent
     * {@code target} path takes precedence for implementations that track a
     * single location per artifact. Implementations that track multiple
     * locations (e.g. {@link SbomArtifactRecorder}) may retain all of them.</p>
     *
     * @param artifact the Maven artifact that was resolved and installed
     * @param target   the path where the artifact was placed in the distribution
     * @throws IOException if an I/O error occurs while recording
     */
    void record(MavenArtifact artifact, Path target) throws IOException;

    /**
     * Caches a copy of the artifact and records it.
     *
     * <p>Implementations may copy the artifact file from {@code jarSrc} into an
     * internal cache directory before recording. If the artifact has already
     * been recorded, the cached copy is not stored again.</p>
     *
     * @param artifact the Maven artifact to cache
     * @param jarSrc   the source path of the artifact JAR file
     * @throws MavenUniverseException if artifact metadata cannot be resolved
     * @throws IOException            if an I/O error occurs during caching
     */
    void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException;

    /**
     * Records a resource JAR artifact. The default implementation delegates to
     * {@link #record(MavenArtifact, Path)}.
     *
     * @param artifact       the Maven artifact with a {@code resources} classifier
     * @param target         the path where the artifact was placed in the distribution
     * @param resolvedJarPath the resolved path to the actual JAR file on disk
     * @throws IOException if an I/O error occurs while recording
     */
    default void recordResourceJar(MavenArtifact artifact, Path target, Path resolvedJarPath) throws IOException {
        record(artifact, target);
    }

    /**
     * Records a provisioning-tool artifact that is not part of the distribution.
     *
     * <p>Tool artifacts (such as config generators) are resolved during
     * provisioning but are not installed into or referenced from the
     * distribution. The default implementation delegates to
     * {@link #record(MavenArtifact, Path)} with a {@code null} target.
     * Implementations that distinguish distribution content from build
     * tooling (e.g. {@link SbomArtifactRecorder}) may exclude or
     * specially mark these artifacts.</p>
     *
     * @param artifact the Maven artifact used as a provisioning tool
     * @throws IOException if an I/O error occurs while recording
     */
    default void recordToolDependency(MavenArtifact artifact) throws IOException {
        record(artifact, null);
    }

    /**
     * Writes the accumulated artifact manifest to persistent storage.
     *
     * <p>Called once at the end of the provisioning process after all artifacts
     * have been recorded. The format and location of the output depend on the
     * implementation.</p>
     *
     * @throws IOException if an I/O error occurs while writing the manifest
     */
    void writeManifest() throws IOException;
}
