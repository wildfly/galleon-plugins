/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.galleon.plugin.WfInstallPlugin.ArtifactResolver;

/**
 * Simple installer, does nominal installation for fat and thin server.
 * This installer is used when the feature-pack is not transformable.
 *
 * @author jdenise
 */
class SimpleArtifactInstaller extends AbstractArtifactInstaller {

    private final Optional<ArtifactRecorder> artifactRecorder;

    SimpleArtifactInstaller(ArtifactResolver resolver, Path generatedMavenRepo, Optional<ArtifactRecorder> artifactRecorder) {
        super(resolver, generatedMavenRepo);
        this.artifactRecorder = artifactRecorder;
    }

    private static final String RESOURCES_CLASSIFIER = "resources";

    @Override
    String installArtifactFat(MavenArtifact artifact, Path targetDir) throws IOException,
            MavenUniverseException, ProvisioningException {
        final Path targetPath = targetDir.resolve(artifact.getArtifactFileName());
        if (artifactRecorder.isPresent()) {
            if (RESOURCES_CLASSIFIER.equals(artifact.getClassifier())) {
                artifactRecorder.get().recordResourceJar(artifact, targetPath, artifact.getPath());
            } else {
                artifactRecorder.get().record(artifact, targetPath);
            }
        }
        Files.copy(artifact.getPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        return artifact.getArtifactFileName();
    }

    @Override
    String installArtifactThin(MavenArtifact artifact) throws IOException,
            MavenUniverseException, ProvisioningException {
        if (artifactRecorder.isPresent()) {
            if (RESOURCES_CLASSIFIER.equals(artifact.getClassifier())) {
                artifactRecorder.get().recordResourceJar(artifact, null, artifact.getPath());
            } else {
                artifactRecorder.get().record(artifact, null);
            }
        }
        installInGeneratedRepo(artifact, artifact.getVersion(), artifact.getPath());
        return artifact.getVersion();
    }

    @Override
    public Path installCopiedArtifact(MavenArtifact artifact) throws IOException, ProvisioningException {
        // Although copied artifact are not thin, we copy them in generated repo for consistency.
        // This means that all artifacts provisioned are present.
        installInGeneratedRepo(artifact, artifact.getVersion(), artifact.getPath());
        return artifact.getPath();
    }
}
