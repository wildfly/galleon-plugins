package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChainedArtifactRecorderTestCase {

    @Test
    public void recordDelegatesToAllRecorders() throws Exception {
        final RecordingRecorder first = new RecordingRecorder();
        final RecordingRecorder second = new RecordingRecorder();
        final ChainedArtifactRecorder chain = new ChainedArtifactRecorder(List.of(first, second));

        final MavenArtifact artifact = mavenArtifact("org.test", "test-one");
        final Path target = Path.of("/tmp/test.jar");

        chain.record(artifact, target);

        assertEquals(1, first.recorded.size());
        assertEquals(1, second.recorded.size());
        assertEquals("org.test:test-one:jar:1.0.0", first.recorded.get(0).artifact.getCoordsAsString());
        assertEquals(target, first.recorded.get(0).target);
    }

    @Test
    public void cacheDelegatesToAllRecorders() throws Exception {
        final RecordingRecorder first = new RecordingRecorder();
        final RecordingRecorder second = new RecordingRecorder();
        final ChainedArtifactRecorder chain = new ChainedArtifactRecorder(List.of(first, second));

        final MavenArtifact artifact = mavenArtifact("org.test", "test-one");
        final Path src = Path.of("/tmp/test.jar");

        chain.cache(artifact, src);

        assertEquals(1, first.cached.size());
        assertEquals(1, second.cached.size());
    }

    @Test
    public void recordToolDependencyDelegatesToAllRecorders() throws Exception {
        final RecordingRecorder first = new RecordingRecorder();
        final RecordingRecorder second = new RecordingRecorder();
        final ChainedArtifactRecorder chain = new ChainedArtifactRecorder(List.of(first, second));

        final MavenArtifact artifact = mavenArtifact("org.test", "tool-dep");
        chain.recordToolDependency(artifact);

        assertEquals(1, first.toolDeps.size());
        assertEquals(1, second.toolDeps.size());
        assertEquals("org.test:tool-dep:jar:1.0.0", first.toolDeps.get(0).getCoordsAsString());
    }

    @Test
    public void writeManifestDelegatesToAllRecorders() throws Exception {
        final RecordingRecorder first = new RecordingRecorder();
        final RecordingRecorder second = new RecordingRecorder();
        final ChainedArtifactRecorder chain = new ChainedArtifactRecorder(List.of(first, second));

        chain.writeManifest();

        assertEquals(1, first.manifestWriteCount);
        assertEquals(1, second.manifestWriteCount);
    }

    private static MavenArtifact mavenArtifact(String groupId, String artifactId) {
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId(groupId);
        artifact.setArtifactId(artifactId);
        artifact.setVersion("1.0.0");
        return artifact;
    }

    private static class RecordingRecorder implements ArtifactRecorder {

        final List<RecordedEntry> recorded = new ArrayList<>();
        final List<RecordedEntry> cached = new ArrayList<>();
        final List<MavenArtifact> toolDeps = new ArrayList<>();
        int manifestWriteCount = 0;

        @Override
        public void record(MavenArtifact artifact, Path target) throws IOException {
            recorded.add(new RecordedEntry(artifact, target));
        }

        @Override
        public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
            cached.add(new RecordedEntry(artifact, jarSrc));
        }

        @Override
        public void recordToolDependency(MavenArtifact artifact) throws IOException {
            toolDeps.add(artifact);
        }

        @Override
        public void writeManifest() throws IOException {
            manifestWriteCount++;
        }
    }

    private record RecordedEntry(MavenArtifact artifact, Path target) {}
}
