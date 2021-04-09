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
import java.util.HashSet;
import java.util.Set;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.galleon.plugin.WfInstallPlugin.ArtifactResolver;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer;
import org.wildfly.galleon.plugin.transformer.TransformedArtifact;

/**
 * Abstract Installer for EE9 transformation.
 *
 * @author jdenise
 */
abstract class AbstractEE9ArtifactInstaller extends AbstractArtifactInstaller {

    private final Set<String> transformExcluded = new HashSet<>();
    private final MessageWriter log;
    private final String jakartaTransformSuffix;
    private final Path jakartaTransformConfigsDir;
    private final JakartaTransformer.LogHandler logHandler;
    private final boolean jakartaTransformVerbose;
    private final ProvisioningRuntime runtime;
    private final WfInstallPlugin plugin;

    AbstractEE9ArtifactInstaller(ArtifactResolver resolver,
            Path generatedMavenRepo,
            Set<String> transformExcluded,
            WfInstallPlugin plugin,
            String jakartaTransformSuffix,
            Path jakartaTransformConfigsDir,
            JakartaTransformer.LogHandler logHandler,
            boolean jakartaTransformVerbose,
            ProvisioningRuntime runtime) {
        super(resolver, generatedMavenRepo);
        this.plugin = plugin;
        this.transformExcluded.addAll(transformExcluded);
        this.log = plugin.log;
        this.jakartaTransformSuffix = jakartaTransformSuffix;
        this.jakartaTransformConfigsDir = jakartaTransformConfigsDir;
        this.logHandler = logHandler;
        this.jakartaTransformVerbose = jakartaTransformVerbose;
        this.runtime = runtime;
    }

    protected TransformedArtifact transform(MavenArtifact artifact, Path targetDir) throws IOException {
        TransformedArtifact a = JakartaTransformer.transform(jakartaTransformConfigsDir, artifact.getPath(), targetDir, jakartaTransformVerbose, logHandler);
        return a;
    }

    protected boolean isOverriddenArtifact(MavenArtifact artifact) throws ProvisioningException {
      return plugin.isOverriddenArtifact(artifact);
    }

    protected boolean isExcludedFromTransformation(MavenArtifact artifact) {
        if (transformExcluded.contains(ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()).toString())) {
            if (log.isVerboseEnabled()) {
                log.verbose("Excluding " + artifact + " from EE9 transformation");
            }
            return true;
        }
        return false;
    }

     String getTransformedArtifactFileName(String version, String fileName) {
        final int endVersionIndex = fileName.lastIndexOf(version) + version.length();
        return fileName.substring(0, endVersionIndex) + jakartaTransformSuffix + fileName.substring(endVersionIndex);
    }

     String getTransformedVersion(String version) {
         return version + jakartaTransformSuffix;
     }

     ProvisioningRuntime getRuntime() {
         return runtime;
     }

    Path setupOverriddenArtifact(MavenArtifact mavenArtifact) throws IOException, MavenUniverseException, ProvisioningException {
        String transformedFileName = getTransformedArtifactFileName(mavenArtifact.getVersion(),
                mavenArtifact.getPath().getFileName().toString());
        Path transformedFile = tryTransformation(mavenArtifact, transformedFileName);
        String gav = ArtifactCoords.newGav(mavenArtifact.getGroupId(), mavenArtifact.getArtifactId(), mavenArtifact.getVersion()).toString();
        if (transformedFile == null) {
            transformExcluded.add(gav);
        }
        return transformedFile;
    }

    void excludeFromTransformation(MavenArtifact artifact) {
        String gav = ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()).toString();
        transformExcluded.add(gav);
    }

    private Path tryTransformation(MavenArtifact artifact, String transformedFileName) throws IOException {
        // We don't know the state of this artifact, we must transform it.
        Path transformedFile = runtime.getTmpPath(transformedFileName);
        Files.createDirectories(transformedFile);
        Files.deleteIfExists(transformedFile);
        TransformedArtifact transformedArtifact = transform(artifact, transformedFile);
        if (!transformedArtifact.isTransformed()) {
            transformedFile = null;
        }
        return transformedFile;
    }
}
