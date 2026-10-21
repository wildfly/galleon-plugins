/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
