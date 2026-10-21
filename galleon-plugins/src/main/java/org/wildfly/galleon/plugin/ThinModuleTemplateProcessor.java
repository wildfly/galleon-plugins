/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
 * The installer is in charge to compute the correct version to be referenced.
 *
 * @author jdenise
 */
class ThinModuleTemplateProcessor extends AbstractModuleTemplateProcessor {

    ThinModuleTemplateProcessor(WfInstallPlugin plugin,
            AbstractArtifactInstaller installer, Path targetPath, ModuleTemplate template, Map<String, String> versionProps,
            boolean channelArtifactResolution, boolean requireChannel) {
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution, requireChannel, false);
    }

    ThinModuleTemplateProcessor(WfInstallPlugin plugin,
            AbstractArtifactInstaller installer, Path targetPath, ModuleTemplate template, Map<String, String> versionProps,
            boolean channelArtifactResolution, boolean requireChannel, boolean includeIncompleteCoords) {
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution, requireChannel, includeIncompleteCoords);
    }

    @Override
    protected void processArtifact(ModuleArtifact moduleArtifact) throws IOException, MavenUniverseException, ProvisioningException {
        MavenArtifact artifact = moduleArtifact.getMavenArtifact();
        String installedVersion = getInstaller().installArtifactThin(artifact);
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
