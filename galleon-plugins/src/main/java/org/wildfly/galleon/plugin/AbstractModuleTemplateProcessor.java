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
import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Elements;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * Abstract template processor that implements logic common to fat and thin
 * server.
 *
 * @author jdenise
 */
abstract class AbstractModuleTemplateProcessor {

    class ModuleArtifact {

        private final Element element;
        boolean jandex;
        String coordsStr;
        private MavenArtifact artifact;
        private final Attribute attribute;

        ModuleArtifact(Element element) {
            this.element = element;
            assert element.getLocalName().equals("artifact");
            attribute = element.getAttribute("name");
            coordsStr = attribute.getValue();
            if (coordsStr.startsWith("${") && coordsStr.endsWith("}")) {
                coordsStr = coordsStr.substring(2, coordsStr.length() - 1);
                final int optionsIndex = coordsStr.indexOf('?');
                if (optionsIndex >= 0) {
                    jandex = coordsStr.indexOf("jandex", optionsIndex) >= 0;
                    coordsStr = coordsStr.substring(0, optionsIndex);
                }
                coordsStr = versionProps.get(coordsStr);
            }
        }

        MavenArtifact getMavenArtifact() throws IOException {
            if (coordsStr == null) {
                return null;
            }
            if (artifact == null) {
                try {
                    artifact = Utils.toArtifactCoords(versionProps, coordsStr, false, channelArtifactResolution);
                } catch (ProvisioningException e) {
                    throw new IOException("Failed to resolve full coordinates for " + coordsStr, e);
                }
                plugin.log.verbose("Resolving %s", artifact);
                try {
                    installer.getArtifactResolver().resolve(artifact);
                    if (channelArtifactResolution) {
                        plugin.log.verbose("Resolved %s", artifact);
                    }
                } catch (ProvisioningException e) {
                    throw new IOException("Failed to resolve artifact " + artifact, e);
                }
            }
            return artifact;
        }

        boolean hasMavenArtifact() throws IOException {
            return getMavenArtifact() != null;
        }

        boolean isJandex() {
            return jandex;
        }

        void updateFatArtifact(String finalFileName) {
            element.setLocalName("resource-root");
            attribute.setLocalName("path");
            attribute.setValue(finalFileName);
        }

        void updateThinArtifact(String coords) {
            attribute.setValue(coords);
        }

    }

    private final ModuleTemplate template;
    private final Map<String, String> versionProps;
    private final WfInstallPlugin plugin;
    private final AbstractArtifactInstaller installer;
    private final Path targetDir;
    private final boolean channelArtifactResolution;

    AbstractModuleTemplateProcessor(WfInstallPlugin plugin, AbstractArtifactInstaller installer, Path targetPath,
            ModuleTemplate template, Map<String, String> versionProps, boolean channelArtifactResolution) {
        this.template = template;
        this.versionProps = versionProps;
        this.plugin = plugin;
        this.installer = installer;
        this.targetDir = targetPath.getParent();
        this.channelArtifactResolution = channelArtifactResolution;
    }

    AbstractArtifactInstaller getInstaller() {
        return installer;
    }

    WfInstallPlugin getPlugin() {
        return plugin;
    }

    Path getTargetDir() {
        return targetDir;
    }

    MessageWriter getLog() {
        return plugin.log;
    }

    void process() throws ProvisioningException, IOException {
        if (template.isModule()) {
            processModuleVersion();
            processArtifacts();
        }
    }

    void processModuleVersion() throws ProvisioningException {
        // replace version, if any
        final Attribute versionAttribute = template.getRootElement().getAttribute("version");
        if (versionAttribute != null) {
            final String versionExpr = versionAttribute.getValue();
            if (versionExpr.startsWith("${") && versionExpr.endsWith("}")) {
                final String exprBody = versionExpr.substring(2, versionExpr.length() - 1);
                final int optionsIndex = exprBody.indexOf('?');
                final String artifactName;
                if (optionsIndex > 0) {
                    artifactName = exprBody.substring(0, optionsIndex);
                } else {
                    artifactName = exprBody;
                }
                final MavenArtifact artifact = Utils.toArtifactCoords(versionProps, artifactName, false, channelArtifactResolution);
                if (artifact != null) {
                    versionAttribute.setValue(artifact.getVersion());
                }
            }
        }
    }

    void processArtifacts() throws IOException, MavenUniverseException, ProvisioningException {
        Elements artifacts = template.getArtifacts();
        if (artifacts == null) {
            return;
        }
        final int artifactCount = artifacts.size();
        for (int i = 0; i < artifactCount; i++) {
            final ModuleArtifact moduleArtifact = new ModuleArtifact(artifacts.get(i));
            if (moduleArtifact.hasMavenArtifact()) {
                Path artifactPath = moduleArtifact.getMavenArtifact().getPath();
                processArtifact(moduleArtifact);
                plugin.processSchemas(moduleArtifact.getMavenArtifact().getGroupId(), artifactPath);
            }
        }
    }

    protected abstract void processArtifact(ModuleArtifact artifact) throws IOException, MavenUniverseException, ProvisioningException;
}
