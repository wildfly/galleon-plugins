/*
 * Copyright 2016-2026 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import dev.cyberstamp.maven.assembly.sbom.ArtifactCoords;
import dev.cyberstamp.maven.assembly.sbom.BundledArtifactScanner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.wildfly.galleon.maven.shaded.FatJar;
import org.wildfly.galleon.maven.shaded.ShadedArtifactsXmlWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import static org.wildfly.galleon.maven.MavenProjectArtifactVersions.SYSTEM;
import static org.wildfly.galleon.maven.MavenProjectArtifactVersions.TEST_JAR;

/**
 * Discover the shaded dependencies, output an XML file to contain the artifacts that shade and the shaded dependencies.
 */
@Mojo(name = "discover-shaded-artifacts", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class ShadedArtifactsDiscoveryMojo extends AbstractMojo {

    @Component
    private ProjectBuilder mavenProjectBuilder;

    @Component
    private MavenProjectHelper projectHelper;

    @Component
    protected RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(required = true)
    protected File outputFile;
    @Parameter
    protected List<String> excludeGA = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Map<String, Set<String>> mapping = new HashMap<>();
            for (String ga : excludeGA) {
                String[] arr = ga.split(":");
                Set<String> aid = mapping.get(arr[0]);
                if (aid == null) {
                    aid = new HashSet<>();
                    mapping.put(arr[0], aid);
                }
                aid.add(arr[1]);
            }
            List<FatJar> lst = new ArrayList<>();
            for (Artifact artifact : project.getArtifacts()) {
                if (TEST_JAR.equals(artifact.getType()) || SYSTEM.equals(artifact.getScope())) {
                    continue;
                }
                Set<String> aid = mapping.get(artifact.getGroupId());
                if (aid != null) {
                    if (aid.contains("*") || aid.contains(artifact.getArtifactId())) {
                        continue;
                    }
                }
                ArtifactCoords coords = new ArtifactCoords(artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getVersion(), artifact.getType(), artifact.getClassifier());
                List<ArtifactCoords> ret = BundledArtifactScanner.bundledNonOwner(artifact.getFile().toPath(), coords);
                if (!ret.isEmpty()) {
                    getLog().info("Found shaded dependencies in " + artifact.getFile().toPath().getFileName() + ": " + ret);
                    lst.add(new FatJar(coords, ret));
                }
            }
            ShadedArtifactsXmlWriter.write(lst, outputFile.toPath());
            getLog().info("Shaded artifacts descriptor written to " + outputFile);
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }
}
