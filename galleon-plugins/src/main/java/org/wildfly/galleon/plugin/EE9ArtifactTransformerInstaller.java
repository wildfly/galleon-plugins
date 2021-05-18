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
 * Installer that does actual EE9 transformation.
 * Such installer is used for fat server with transformation enabled
 * and thin server with a generated maven repository.
 * This installer populates a cache for fat server that can be used when example configs
 * are generated.
 * Overridden artifacts, if not excluded are transformed. If transformation results in a non transformed
 * artifact, the original artifact is used.
 * @author jdenise
 */
class EE9ArtifactTransformerInstaller extends AbstractEE9ArtifactInstaller {

    EE9ArtifactTransformerInstaller(ArtifactResolver resolver,
            Path generatedMavenRepo,
            Set<String> transformExcluded,
            WfInstallPlugin plugin,
            String jakartaTransformSuffix,
            Path jakartaTransformConfigsDir,
            JakartaTransformer.LogHandler logHandler,
            boolean jakartaTransformVerbose,
            ProvisioningRuntime runtime) {
        super(resolver, generatedMavenRepo,
                transformExcluded, plugin, jakartaTransformSuffix,
                jakartaTransformConfigsDir, logHandler, jakartaTransformVerbose, runtime);
    }

    @Override
    String installArtifactFat(MavenArtifact artifact, Path targetDir, Path localCache) throws IOException,
            MavenUniverseException, ProvisioningException {
        String artifactFileName = artifact.getArtifactFileName();
        Path transformedFile = null;
        if (isOverriddenArtifact(artifact)) {
            transformedFile = setupOverriddenArtifact(artifact);
        }
        String version = artifact.getVersion();
        if (!isExcludedFromTransformation(artifact)) {
            artifactFileName = getTransformedArtifactFileName(artifact.getVersion(), artifactFileName);
            if (transformedFile == null) {
                Path transformedPath = targetDir.resolve(artifactFileName);
                transform(artifact, transformedPath);
            } else {
                Files.copy(transformedFile, targetDir.resolve(artifactFileName), StandardCopyOption.REPLACE_EXISTING);
            }
            version = getTransformedVersion(version);
        } else {
            Files.copy(artifact.getPath(), targetDir.resolve(artifactFileName), StandardCopyOption.REPLACE_EXISTING);
        }
        if (localCache != null) {
            Path pomFile = getPomArtifactPath(artifact, getArtifactResolver());
            Path versionPath = getLocalRepoPath(artifact, version, localCache);
            Files.copy(pomFile, versionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(targetDir.resolve(artifactFileName), versionPath.resolve(artifactFileName), StandardCopyOption.REPLACE_EXISTING);
        }
        return artifactFileName;
    }

    @Override
    String installArtifactThin(MavenArtifact artifact) throws IOException,
            MavenUniverseException, ProvisioningException {
        Path transformedFile = null;
        if (isOverriddenArtifact(artifact)) {
            transformedFile = setupOverriddenArtifact(artifact);
        }
        String version = artifact.getVersion();
        Path versionPath = getLocalRepoPath(artifact, version, getGeneratedMavenRepo());
        if (!isExcludedFromTransformation(artifact)) {
            version = getTransformedVersion(artifact.getVersion());
            versionPath = getLocalRepoPath(artifact, version, getGeneratedMavenRepo());
            if (transformedFile == null) {
                String name = getTransformedArtifactFileName(artifact.getVersion(), artifact.getPath().getFileName().toString());
                Path transformedTarget = versionPath.resolve(name);
                if (!Files.exists(transformedTarget)) {
                    transform(artifact, transformedTarget);
                }
            } else {
                Files.copy(transformedFile, versionPath.resolve(transformedFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.copy(artifact.getPath(), versionPath.resolve(artifact.getArtifactFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Path pomFile = getPomArtifactPath(artifact, getArtifactResolver());
        Files.copy(pomFile, versionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        return version;
    }

    @Override
    Path installCopiedArtifact(MavenArtifact artifact) throws IOException, ProvisioningException {
        Path transformedFile = null;
        if (isOverriddenArtifact(artifact)) {
            transformedFile = setupOverriddenArtifact(artifact);
        }
        Path path = artifact.getPath();
        String version = artifact.getVersion();
        if (!isExcludedFromTransformation(artifact)) {
            if (transformedFile == null) {
                String transformedFileName = getTransformedArtifactFileName(artifact.getVersion(),
                        artifact.getPath().getFileName().toString());
                path = getRuntime().getTmpPath().resolve(transformedFileName);
                Files.createDirectories(path);
                Files.deleteIfExists(path);
                transform(artifact, path);
            } else {
                path = transformedFile;
            }
            version = getTransformedVersion(version);
        }

        //Populate generated maven repo if any
        installInGeneratedRepo(artifact, version, path);
        return path;
    }
}
