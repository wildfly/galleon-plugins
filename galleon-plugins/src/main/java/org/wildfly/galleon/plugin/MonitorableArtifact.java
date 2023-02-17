package org.wildfly.galleon.plugin;

import org.jboss.galleon.progresstracking.ProgressTracker;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

import java.nio.file.Path;

class MonitorableArtifact extends MavenArtifact {

    private final ProgressTracker<MavenArtifact> tracker;
    private final MavenArtifact delegate;

    MonitorableArtifact(MavenArtifact delegate, ProgressTracker<MavenArtifact> tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
    }

    @Override
    public String getGroupId() {
        return delegate.getGroupId();
    }

    @Override
    public MavenArtifact setGroupId(String groupId) {
        return delegate.setGroupId(groupId);
    }

    @Override
    public String getArtifactId() {
        return delegate.getArtifactId();
    }

    @Override
    public MavenArtifact setArtifactId(String artifactId) {
        return delegate.setArtifactId(artifactId);
    }

    @Override
    public boolean hasVersion() {
        return delegate.hasVersion();
    }

    @Override
    public String getVersion() {
        return delegate.getVersion();
    }

    @Override
    public MavenArtifact setVersion(String version) {
        return delegate.setVersion(version);
    }

    @Override
    public String getClassifier() {
        return delegate.getClassifier();
    }

    @Override
    public MavenArtifact setClassifier(String classifier) {
        return delegate.setClassifier(classifier);
    }

    @Override
    public String getExtension() {
        return delegate.getExtension();
    }

    @Override
    public MavenArtifact setExtension(String extension) {
        return delegate.setExtension(extension);
    }

    @Override
    public String getVersionRange() {
        return delegate.getVersionRange();
    }

    @Override
    public MavenArtifact setVersionRange(String versionRange) {
        return delegate.setVersionRange(versionRange);
    }

    @Override
    public Path getPath() {
        return delegate.getPath();
    }

    @Override
    public boolean isResolved() {
        return delegate.isResolved();
    }

    @Override
    public String getArtifactFileName() throws MavenUniverseException {
        return delegate.getArtifactFileName();
    }

    @Override
    public String getCoordsAsString() {
        return delegate.getCoordsAsString();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    @Override
    public MavenArtifact setPath(Path localArtifact) {
        tracker.processed(delegate);
        return delegate.setPath(localArtifact);
    }
}
