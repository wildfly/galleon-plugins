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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.galleon.plugin.WfInstallPlugin.ArtifactResolver;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer;

/**
 * Installer that copies already transformed artifact from the
 * provisioningRepository and compute transformed version for thin server.
 * Such installer is used for fat and thin server with transformation disabled
 * and provisioning maven repository being set.
 * The use-case is where the transformed repository has already been created and the goal is to reuse it,
 * e.g. on the WF testsuite.
 * Overridden artifacts, if not excludedand not present in the provisioning repository are transformed.
 * If transformation results in a non transformed artifact, the original artifact is used.
 *
 * @author jdenise
 */
class EE9ArtifactInstaller extends AbstractEE9ArtifactInstaller {

    private final Path provisioningMavenRepo;

    EE9ArtifactInstaller(ArtifactResolver resolver,
            Path generatedMavenRepo,
            Set<String> transformExcluded,
            WfInstallPlugin plugin,
            String jakartaTransformSuffix,
            Path jakartaTransformConfigsDir,
            JakartaTransformer.LogHandler logHandler,
            boolean jakartaTransformVerbose,
            ProvisioningRuntime runtime,
            Path provisioningMavenRepo) {
        super(resolver, generatedMavenRepo,
                transformExcluded, plugin, jakartaTransformSuffix,
                jakartaTransformConfigsDir, logHandler, jakartaTransformVerbose, runtime);
        this.provisioningMavenRepo = provisioningMavenRepo;
    }

    boolean isOverriddenTransformed(MavenArtifact artifact) throws IOException {
        String transformedVersion = getTransformedVersion(artifact.getVersion());
        Path transformedPath = getLocalRepoPath(artifact, transformedVersion, provisioningMavenRepo, false);
        boolean transformed = false;
        if (Files.exists(transformedPath)) {
            // This repository already contains the transformed artifact. This artifact is not excluded from transformation
            transformed = true;
        } else {
            Path notTransformedPath = getLocalRepoPath(artifact, artifact.getVersion(), provisioningMavenRepo, false);
            if (Files.exists(notTransformedPath)) {
                // The artifact is not transformed and is present in the repo, so can be excluded from transformation.
                excludeFromTransformation(artifact);
            }
        }
        return transformed;
    }

    Path handleOverriddenTransformation(MavenArtifact artifact) throws IOException, ProvisioningException {
        //First check if it is already transformed  in the repo and exclude it if present as not transformed.
        boolean isTransformed = isOverriddenTransformed(artifact);
        Path path = artifact.getPath();
        if (!isTransformed && !isExcludedFromTransformation(artifact)) {
            // Transform attempt and install in provisioningMavenRepo.
            Path pomFile = getPomArtifactPath(artifact, getArtifactResolver());
            Path transformedFile = setupOverriddenArtifact(artifact);
            // The provisioningMavenRepo is used when generating the configuration.
            if (transformedFile == null) {
                Path notTransformedVersionPath = getLocalRepoPath(artifact, artifact.getVersion(), provisioningMavenRepo);
                path = notTransformedVersionPath.resolve(artifact.getArtifactFileName());
                Files.copy(artifact.getPath(), path, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(pomFile, notTransformedVersionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Copy the transformed one
                String transformedVersion = getTransformedVersion(artifact.getVersion());
                Path transformedVersionPath = getLocalRepoPath(artifact, transformedVersion, provisioningMavenRepo);
                path = transformedVersionPath.resolve(transformedFile.getFileName());
                Files.copy(transformedFile, path, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(pomFile, transformedVersionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return path;
    }

    @Override
    String installArtifactFat(MavenArtifact artifact, Path targetDir, Path localCache) throws IOException,
            MavenUniverseException, ProvisioningException {
        Path path = artifact.getPath();
        if (isOverriddenArtifact(artifact)) {
            path = handleOverriddenTransformation(artifact);
        }
        Files.copy(path, targetDir.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        return path.getFileName().toString();
    }

    @Override
    String installArtifactThin(MavenArtifact artifact) throws IOException,
            MavenUniverseException, ProvisioningException {
        String version = artifact.getVersion();
        if (isOverriddenArtifact(artifact)) {
            handleOverriddenTransformation(artifact);
        }
        if (!isExcludedFromTransformation(artifact)) {
            version = getTransformedVersion(artifact.getVersion());
        }
        return version;
    }

    @Override
    Path installCopiedArtifact(MavenArtifact artifact) throws IOException, ProvisioningException {
        Path path = artifact.getPath();
        if (isOverriddenArtifact(artifact)) {
            path = handleOverriddenTransformation(artifact);
        }
        return path;
    }
}
