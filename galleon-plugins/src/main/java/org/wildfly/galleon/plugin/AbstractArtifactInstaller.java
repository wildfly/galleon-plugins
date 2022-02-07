/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
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
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.wildfly.galleon.plugin.WfInstallPlugin.ArtifactResolver;

/**
 * An artifact installer captures the logic required to install an artifact during provisioning.
 * It handles the kind of server (thin vs fat).
 *
 * The artifacts in scope are: JBoss Modules modules artifacts and artifacts copied with the CopyArtifact
 * task.
 *
 * This class is an abstract class that exposes the installer contract and offers utilities method to child classes.
 *
 * @author jdenise
 */
abstract class AbstractArtifactInstaller implements ShadedModel.Installer {

    private final Path generatedMavenRepo;
    private final ArtifactResolver resolver;

    AbstractArtifactInstaller(ArtifactResolver resolver, Path generatedMavenRepo) {
        this.resolver = resolver;
        this.generatedMavenRepo = generatedMavenRepo;
    }

    abstract String installArtifactFat(MavenArtifact artifact, Path targetDir) throws IOException,
            ProvisioningException;

    abstract String installArtifactThin(MavenArtifact artifact) throws IOException,
            ProvisioningException;

    @Override
    public abstract Path installCopiedArtifact(MavenArtifact artifact) throws IOException, ProvisioningException;

    Path getGeneratedMavenRepo() {
        return generatedMavenRepo;
    }

    ArtifactResolver getArtifactResolver() {
        return resolver;
    }

    static Path getPomArtifactPath(MavenArtifact artifact, ArtifactResolver resolver) throws ProvisioningException {
        MavenArtifact pomArtifact = new MavenArtifact();
        pomArtifact.setGroupId(artifact.getGroupId());
        pomArtifact.setArtifactId(artifact.getArtifactId());
        pomArtifact.setVersion(artifact.getVersion());
        pomArtifact.setExtension("pom");
        resolver.resolve(pomArtifact);
        return pomArtifact.getPath();
    }

    static Path getLocalRepoPath(MavenArtifact artifact, String version, Path repo) throws IOException {
        return getLocalRepoPath(artifact, version, repo, true);
    }

    static Path getLocalRepoPath(MavenArtifact artifact, String version, Path repo, boolean create) throws IOException {
        String grpid = artifact.getGroupId().replaceAll("\\.", Matcher.quoteReplacement(File.separator));
        Path grpidPath = repo.resolve(grpid);
        Path artifactidPath = grpidPath.resolve(artifact.getArtifactId());
        Path versionPath = artifactidPath.resolve(version);
        if (create) {
            Files.createDirectories(versionPath);
        }
        return versionPath;
    }

    void installInGeneratedRepo(MavenArtifact artifact, String version, Path path) throws IOException, ProvisioningException {
        if (getGeneratedMavenRepo() != null) {
            Path versionPath = getLocalRepoPath(artifact, version, getGeneratedMavenRepo());
            Path actualTarget = versionPath.resolve(path.getFileName().toString());
            Files.copy(path, actualTarget, StandardCopyOption.REPLACE_EXISTING);
            Path pomFile = getPomArtifactPath(artifact, getArtifactResolver());
            Files.copy(pomFile, versionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
