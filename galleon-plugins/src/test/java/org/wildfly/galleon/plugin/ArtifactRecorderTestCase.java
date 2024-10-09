package org.wildfly.galleon.plugin;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArtifactRecorderTestCase {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private Path installBase;
    private Path cacheDir;
    private ArtifactRecorder recorder;

    @Before
    public void setUp() throws Exception {
        installBase = temp.newFolder("server-root").toPath();
        cacheDir = installBase.resolve("cache");
        recorder = new ArtifactRecorder(installBase, cacheDir);
    }

    @Test
    public void recordExternalArtifact() throws Exception {
        recorder.record(mavenArtifact("org.test", "test-one"), createArtifactFile("test.jar"));

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly("org.test:test-one:jar:1.0.0::*::test.jar");
    }

    @Test
    public void recordExternalArtifactTwiceResultsInSingleEntry() throws Exception {
        recorder.record(mavenArtifact("org.test", "test-one"), createArtifactFile("test.jar"));

        recorder.record(mavenArtifact("org.test", "test-one"), createArtifactFile("test/test.jar"));

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly("org.test:test-one:jar:1.0.0::*::test/test.jar");
    }

    @Test
    public void cacheArtifactResultsInCopySavedInCache() throws Exception {
        recorder.cache(mavenArtifact("org.test", "test-one"), createArtifactFile("test.jar"));

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly("org.test:test-one:jar:1.0.0::*::cache/test-one-1.0.0.jar");

        assertTrue("File expected to exist, but not found: " + cacheDir.resolve("test-one-1.0.0.jar"),
                Files.exists(cacheDir.resolve("test-one-1.0.0.jar")));
    }

    @Test
    public void cacheArtifactTwice() throws Exception {
        final Path artifactFile = createArtifactFile("test.jar");

        recorder.cache(mavenArtifact("org.test", "test-one"), artifactFile);

        recorder.cache(mavenArtifact("org.test", "test-one"), artifactFile);

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly("org.test:test-one:jar:1.0.0::*::cache/test-one-1.0.0.jar");

        assertTrue("File expected to exist, but not found: " + cacheDir.resolve("test-one-1.0.0.jar"),
                Files.exists(cacheDir.resolve("test-one-1.0.0.jar")));
    }

    @Test
    public void recordedArtifactOverwritesCachedArtifact() throws Exception {

        recorder.cache(mavenArtifact("org.test", "test-one"), createArtifactFile("test.jar"));

        recorder.record(mavenArtifact("org.test", "test-one"), createArtifactFile("test/test.jar"));

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly("org.test:test-one:jar:1.0.0::*::test/test.jar");

        assertFalse("File expected not to exist, but found " + cacheDir.resolve("test-one-1.0.0.jar"),
                Files.exists(cacheDir.resolve("test-one-1.0.0.jar")));
    }

    @Test
    public void recordTwoArtifacts() throws Exception {

        recorder.record(mavenArtifact("org.test", "test-one"), createArtifactFile("test-one.jar"));

        recorder.record(mavenArtifact("org.test", "test-two"), createArtifactFile("test-two.jar"));

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly(
                "org.test:test-one:jar:1.0.0::*::test-one.jar",
                "org.test:test-two:jar:1.0.0::*::test-two.jar"
                );
    }

    @Test
    public void cacheDoesntStoreAlreadyRecordedArtifact() throws Exception {
        final Path artifactFile = createArtifactFile("test-one.jar");
        recorder.record(mavenArtifact("org.test", "test-one"), artifactFile);

        recorder.cache(mavenArtifact("org.test", "test-one"), artifactFile);

        recorder.writeCacheManifest();

        assertRecordedArtifactContainOnly(
                "org.test:test-one:jar:1.0.0::*::test-one.jar"
        );
        assertFalse("File expected not to exist, but found " + cacheDir.resolve("test-one-1.0.0.jar"),
                Files.exists(cacheDir.resolve("test-one-1.0.0.jar")));
    }

    private void assertRecordedArtifactContainOnly(String... lines) throws IOException {
        final List<String> artifactList = Files.readAllLines(cacheDir.resolve(ArtifactRecorder.ARTIFACT_LIST_FILE));

        final String errorMessage = String.format("Expected recorded lines to contain %n[%s]%n but got %n[%s]",
                String.join(",", lines), String.join(",", artifactList));
        assertEquals(errorMessage, lines.length, artifactList.size());

        for (int i = 0; i < lines.length; i++) {
            final String[] split = lines[i].split("\\*");
            final String prefix = split[0];
            final String suffix = split[1];

            assertTrue(errorMessage, artifactList.get(i).startsWith(prefix));
            assertTrue(errorMessage, artifactList.get(i).endsWith(suffix));
        }

    }

    private static MavenArtifact mavenArtifact(String groupId, String artifactId) {
        final MavenArtifact artifactCoord = new MavenArtifact();
        artifactCoord.setGroupId(groupId);
        artifactCoord.setArtifactId(artifactId);
        artifactCoord.setVersion("1.0.0");
        return artifactCoord;
    }

    private Path createArtifactFile(String path) throws IOException {
        final Path artifact = installBase.resolve(path);
        if (!Files.exists(artifact.getParent())) {
            Files.createDirectories(artifact.getParent());
        }
        Files.createFile(artifact);
        return artifact;
    }
}
