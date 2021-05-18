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
import java.nio.file.Path;
import java.util.Map;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * A Template processor that process templates when provisioning thin server. In
 * this case module.xml artifacts are updated with a version.
 * The installer is in charge to compute the correct version to be referenced. Artifact version could be modified
 * in case of Jakarta transformation.
 *
 * @author jdenise
 */
class ThinModuleTemplateProcessor extends AbstractModuleTemplateProcessor {

    ThinModuleTemplateProcessor(WfInstallPlugin plugin,
            AbstractArtifactInstaller installer, Path targetPath, ModuleTemplate template, Map<String, String> versionProps) {
        super(plugin, installer, targetPath, template, versionProps);
    }

    @Override
    protected void processArtifact(ModuleArtifact moduleArtifact) throws IOException, MavenUniverseException, ProvisioningException {
        MavenArtifact artifact = moduleArtifact.getMavenArtifact();
        String installedVersion = getInstaller().installArtifactThin(artifact);
        // ignore jandex variable, just resolve coordinates to a string
        final StringBuilder buf = new StringBuilder();
        buf.append(artifact.getGroupId());
        buf.append(':');
        buf.append(artifact.getArtifactId());
        buf.append(':');
        buf.append(installedVersion);
        if (!artifact.getClassifier().isEmpty()) {
            buf.append(':');
            buf.append(artifact.getClassifier());
        }
        moduleArtifact.updateThinArtifact(buf.toString());

    }
}
