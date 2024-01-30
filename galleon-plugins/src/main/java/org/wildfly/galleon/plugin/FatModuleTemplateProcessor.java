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
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution, requireChannel);
    }

    @Override
    protected void processArtifact(ModuleArtifact artifact) throws IOException, MavenUniverseException, ProvisioningException {
        String finalFileName = getInstaller().installArtifactFat(artifact.getMavenArtifact(), getTargetDir());
        artifact.updateFatArtifact(finalFileName);
    }
}
