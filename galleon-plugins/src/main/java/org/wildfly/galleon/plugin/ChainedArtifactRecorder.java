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
import java.util.List;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * An {@link ArtifactRecorder} that delegates every operation to a list of
 * underlying recorders.
 *
 * <p>This allows multiple recording strategies (e.g. {@link ArtifactsTxtRecorder}
 * and {@link CycloneDxSbomGenerator}) to be active simultaneously during a
 * single provisioning run. Each method call is forwarded to every delegate
 * in list order.</p>
 *
 * @see ArtifactRecorder
 */
public class ChainedArtifactRecorder implements ArtifactRecorder {

    private final List<ArtifactRecorder> delegates;

    /**
     * Creates a chained recorder that delegates to the given recorders.
     *
     * @param delegates the list of recorders to delegate to; must not be null or empty
     */
    public ChainedArtifactRecorder(List<ArtifactRecorder> delegates) {
        this.delegates = delegates;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the call to every delegate in list order.</p>
     */
    @Override
    public void record(MavenArtifact artifact, Path target) throws IOException {
        for (ArtifactRecorder delegate : delegates) {
            delegate.record(artifact, target);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the call to every delegate in list order.</p>
     */
    @Override
    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        for (ArtifactRecorder delegate : delegates) {
            delegate.cache(artifact, jarSrc);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the call to every delegate in list order.</p>
     */
    @Override
    public void recordToolDependency(MavenArtifact artifact) throws IOException {
        for (ArtifactRecorder delegate : delegates) {
            delegate.recordToolDependency(artifact);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the call to every delegate in list order.</p>
     */
    @Override
    public void recordResourceJar(MavenArtifact artifact, Path target, Path resolvedJarPath) throws IOException {
        for (ArtifactRecorder delegate : delegates) {
            delegate.recordResourceJar(artifact, target, resolvedJarPath);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the call to every delegate in list order.</p>
     */
    @Override
    public void writeManifest() throws IOException {
        for (ArtifactRecorder delegate : delegates) {
            delegate.writeManifest();
        }
    }
}
