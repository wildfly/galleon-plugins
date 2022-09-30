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

    SimpleArtifactInstaller(ArtifactResolver resolver, Path generatedMavenRepo) {
        super(resolver, generatedMavenRepo);
    }

    @Override
    String installArtifactFat(MavenArtifact artifact, Path targetDir) throws IOException,
            MavenUniverseException, ProvisioningException {
        Files.copy(artifact.getPath(), targetDir.resolve(artifact.getArtifactFileName()), StandardCopyOption.REPLACE_EXISTING);
        return artifact.getArtifactFileName();
    }

    @Override
    String installArtifactThin(MavenArtifact artifact) throws IOException,
            MavenUniverseException, ProvisioningException {
        installInGeneratedRepo(artifact, artifact.getVersion(), artifact.getPath());
        return artifact.getVersion();
    }

    @Override
    Path installCopiedArtifact(MavenArtifact artifact) throws IOException, ProvisioningException {
        // Although copied artifact are not thin, we copy them in generated repo for consistency.
        // This means that all artifacts provisioned are present.
        installInGeneratedRepo(artifact, artifact.getVersion(), artifact.getPath());
        return artifact.getPath();
    }
}
