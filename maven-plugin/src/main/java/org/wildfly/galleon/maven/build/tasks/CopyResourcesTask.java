/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.galleon.maven.build.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo;
import org.wildfly.galleon.maven.ArtifactCoordsUtil;
import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 *
 * @author Alexey Loubyansky
 */
public class CopyResourcesTask implements ResourcesTask {

    private String path;
    private String artifact;
    private String to;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getArtifact() {
        return artifact;
    }

    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    @Override
    public void execute(AbstractFeaturePackBuildMojo builder, Path resourcesDir) throws MojoExecutionException {
        final String error = getValidationErrors();
        if(error != null) {
            throw new MojoExecutionException(error);
        }
        Path target = resourcesDir.resolve(to);
        final Path src;
        if (artifact == null) {
            src = Paths.get(this.path);
            if (!Files.exists(src)) {
                throw new MojoExecutionException("Copy task source " + src + " does not exist");
            }
            if (!Files.isDirectory(src)) {
                if (to.charAt(to.length() - 1) == '/') {
                    target = target.resolve(src.getFileName());
                }
                mkdirs(target.getParent());
            } else {
                mkdirs(target);
            }
        } else {
            ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(artifact, "jar");
            if(coords.getVersion() == null) {
                coords = ArtifactCoordsUtil.fromJBossModules(builder.resolveVersion(artifact), "jar");
            }
            try {
                src = builder.resolveArtifact(coords);
            } catch (ProvisioningException e) {
                throw new MojoExecutionException("Failed to resolve " + coords, e);
            }
            if (to.charAt(to.length() - 1) == '/') {
                target = target.resolve(coords.getArtifactId() + '-' + coords.getVersion() + '.' + coords.getExtension());
            }
            mkdirs(target.getParent());
        }
        try {
            IoUtils.copy(src, target);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy " + src + " to " + target, e);
        }
    }

    private void mkdirs(Path p) throws MojoExecutionException {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create directory " + p, e);
        }
    }

    public String getValidationErrors() {
        String error = assertTargetSet();
        if(error == null) {
            error = assertSourceSet();
        }
        return error;
    }

    private String assertTargetSet() {
        if(to == null) {
            return "The copy task target has not been configured";
        }
        return null;
    }

    private String assertSourceSet() {
        if(path == null && artifact == null ||
                path != null && artifact != null) {
            return "Either path or artifact has to be configured as the copy task source";
        }
        return null;
    }
}
