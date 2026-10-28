/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.nio.file.Path;

import org.jboss.galleon.DefaultMessageWriter;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Verifies {@link ErrorHandlingArtifactRecorder}: it enforces the
 * {@code jboss-cyclonedx-fail-on-error} policy around a delegate recorder,
 * rethrowing when fatal and downgrading to a warning (and disabling further
 * recording) otherwise.
 */
public class ErrorHandlingArtifactRecorderTestCase {

    /** A recorder whose operations always fail, and that counts how often it was called. */
    private static final class FailingRecorder implements ArtifactRecorder {
        int recordCalls;
        int writeCalls;

        @Override
        public void record(MavenArtifact artifact, Path target) throws IOException {
            recordCalls++;
            throw new IOException("record failed");
        }

        @Override
        public void cache(MavenArtifact artifact, Path jarSrc) {
        }

        @Override
        public void writeManifest() throws IOException {
            writeCalls++;
            throw new IOException("write failed");
        }
    }

    private static final MavenArtifact ARTIFACT = new MavenArtifact();

    @Test
    public void rethrowsWhenFatal() {
        final ErrorHandlingArtifactRecorder recorder = new ErrorHandlingArtifactRecorder(
                new FailingRecorder(), true, DefaultMessageWriter.getDefaultInstance(), "SBOM generation failed");
        try {
            recorder.writeManifest();
            fail("expected IOException when fail-on-error is enabled");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void warnsAndContinuesWhenNonFatal() throws Exception {
        final ErrorHandlingArtifactRecorder recorder = new ErrorHandlingArtifactRecorder(
                new FailingRecorder(), false, DefaultMessageWriter.getDefaultInstance(), "SBOM generation failed");
        // Must not throw: the failure is downgraded to a warning.
        recorder.writeManifest();
    }

    @Test
    public void stopsDelegatingAfterFirstNonFatalFailure() throws Exception {
        final FailingRecorder delegate = new FailingRecorder();
        final ErrorHandlingArtifactRecorder recorder = new ErrorHandlingArtifactRecorder(
                delegate, false, DefaultMessageWriter.getDefaultInstance(), "SBOM generation failed");

        recorder.record(ARTIFACT, null); // fails, downgraded
        recorder.record(ARTIFACT, null); // must be skipped, not delegated
        recorder.writeManifest();        // must be skipped, not delegated

        assertEquals("delegate must be called only once before being disabled", 1, delegate.recordCalls);
        assertEquals("no manifest should be written after a prior failure", 0, delegate.writeCalls);
    }
}
