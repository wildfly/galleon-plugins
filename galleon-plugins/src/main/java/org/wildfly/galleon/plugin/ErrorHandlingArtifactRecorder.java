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

import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * An {@link ArtifactRecorder} that applies a failure policy around a delegate
 * recorder, keeping the delegate free of error-handling concerns.
 *
 * <p>When {@code failOnError} is {@code true}, a failure in the delegate is
 * rethrown, aborting provisioning. Otherwise the caller-supplied warning message
 * is logged and further recording is disabled so that no partial output is
 * produced and provisioning can continue.</p>
 *
 * @see ChainedArtifactRecorder
 */
public class ErrorHandlingArtifactRecorder implements ArtifactRecorder {

    private final ArtifactRecorder delegate;
    private final boolean failOnError;
    private final MessageWriter log;
    private final String warningMessage;
    /** Set once a failure is handled under a non-fatal policy, disabling further recording and output. */
    private boolean failed;

    /**
     * @param delegate       the recorder to guard
     * @param failOnError    whether a delegate failure must abort provisioning
     * @param log            the message writer used to warn when non-fatal
     * @param warningMessage the message logged (together with the failure) when non-fatal
     */
    public ErrorHandlingArtifactRecorder(ArtifactRecorder delegate, boolean failOnError, MessageWriter log,
            String warningMessage) {
        this.delegate = delegate;
        this.failOnError = failOnError;
        this.log = log;
        this.warningMessage = warningMessage;
    }

    @Override
    public void record(MavenArtifact artifact, Path target) throws IOException {
        if (failed) {
            return;
        }
        try {
            delegate.record(artifact, target);
        } catch (IOException | RuntimeException e) {
            onFailure(e);
        }
    }

    @Override
    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        if (failed) {
            return;
        }
        try {
            delegate.cache(artifact, jarSrc);
        } catch (MavenUniverseException | IOException | RuntimeException e) {
            onFailure(e);
        }
    }

    @Override
    public void recordResourceJar(MavenArtifact artifact, Path target, Path resolvedJarPath) throws IOException {
        if (failed) {
            return;
        }
        try {
            delegate.recordResourceJar(artifact, target, resolvedJarPath);
        } catch (IOException | RuntimeException e) {
            onFailure(e);
        }
    }

    @Override
    public void recordToolDependency(MavenArtifact artifact) throws IOException {
        if (failed) {
            return;
        }
        try {
            delegate.recordToolDependency(artifact);
        } catch (IOException | RuntimeException e) {
            onFailure(e);
        }
    }

    @Override
    public void writeManifest() throws IOException {
        if (failed) {
            return;
        }
        try {
            delegate.writeManifest();
        } catch (IOException | RuntimeException e) {
            onFailure(e);
        }
    }

    /**
     * Applies the failure policy: rethrows when fatal, otherwise logs the
     * configured warning message (with the failure) and disables further recording.
     *
     * @param e the failure
     * @throws IOException if {@link #failOnError} is set
     */
    private void onFailure(Exception e) throws IOException {
        if (failOnError) {
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        failed = true;
        log.error(e, warningMessage);
    }
}
