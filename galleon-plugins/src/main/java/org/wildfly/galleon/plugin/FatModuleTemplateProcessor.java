/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * A Template processor that process templates when provisioning fat server. In
 * this case artifacts are copied to the modules directory by the associated installer.
 * This processor updates the module.xml with reference to the file copied locally.
 * reference local artifact.
 *
 * @author jdenise
 */
class FatModuleTemplateProcessor extends AbstractModuleTemplateProcessor {

    public FatModuleTemplateProcessor(WfInstallPlugin plugin, AbstractArtifactInstaller installer,
            Path targetPath, ModuleTemplate template,
            Map<String, String> versionProps, boolean channelArtifactResolution, boolean requireChannel) {
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution, requireChannel, false);
    }

    public FatModuleTemplateProcessor(WfInstallPlugin plugin, AbstractArtifactInstaller installer,
            Path targetPath, ModuleTemplate template,
            Map<String, String> versionProps, boolean channelArtifactResolution, boolean requireChannel, boolean includeIncompleteCoords) {
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution, requireChannel, includeIncompleteCoords);
    }

    @Override
    protected void processArtifact(ModuleArtifact artifact) throws IOException, MavenUniverseException, ProvisioningException {
        String finalFileName = getInstaller().installArtifactFat(artifact.getMavenArtifact(), getTargetDir());
        artifact.updateFatArtifact(finalFileName);
    }
}
