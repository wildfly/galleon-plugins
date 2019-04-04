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
package org.wildfly.galleon.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.Universe;
import org.jboss.galleon.universe.UniverseFactoryLoader;
import org.jboss.galleon.universe.UniverseResolver;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenProducer;
import org.jboss.galleon.universe.maven.MavenUniverse;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.Utils;
import org.wildfly.galleon.plugin.WfConstants;

/**
 * Maven repository generator.
 *
 * @author jdenise@redhat.com
 */
@Mojo(name = "generate-maven-repository", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class MavenRepositoryGeneratorMojo extends AbstractMojo {

    @Component
    protected RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> repositories;

    @Parameter(alias = "feature-pack-group-id", required = true)
    private String fpGroupId;

    @Parameter(alias = "feature-pack-artifact-id", required = true)
    private String fpArtifactId;

    @Parameter(alias = "feature-pack-version", required = false)
    private String fpVersion;

    /**
     * Path to a directory in which resolved artifacts are copied. If the
     * directory exists, it is first deleted. The output directory contains
     * artifacts as well as maven pom files. The layout inside the directory is
     * compliant with maven repository layout.
     */
    @Parameter(alias = "provisioning-repo-directory", required = true)
    private File provisioningRepoDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (provisioningRepoDirectory.exists()) {
            IoUtils.recursiveDelete(provisioningRepoDirectory.toPath());
        }
        final MavenProjectArtifactVersions projectArtifacts = MavenProjectArtifactVersions.getInstance(project);
        MavenArtifactRepositoryManager artifactResolver = new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);
        MavenRepoBuilder builder = new MavenRepoBuilder(provisioningRepoDirectory.toPath(), repoSession.getLocalRepository().getBasedir().toPath());
        final UniverseFactoryLoader ufl = UniverseFactoryLoader.getInstance().addArtifactResolver(artifactResolver);
        try {
            // Add feature-pack
            MavenArtifact fpArtifact = new MavenArtifact();
            fpArtifact.setArtifactId(fpArtifactId);
            fpArtifact.setGroupId(fpGroupId);
            if (fpVersion == null) {
                String coords = projectArtifacts.getVersion(fpGroupId + ":" + fpArtifactId);
                if (coords != null) {
                    ArtifactCoords c = ArtifactCoordsUtil.fromJBossModules(coords, null);
                    if (c != null) {
                        fpVersion = c.getVersion();
                    }
                }
            }
            if (fpVersion == null) {
                throw new MojoExecutionException("Version for feature-pack has not been found.");
            }
            fpArtifact.setVersion(fpVersion);
            fpArtifact.setExtension("zip");
            artifactResolver.resolve(fpArtifact);
            Path localPath = fpArtifact.getPath();
            builder.add(localPath);

            try (ProvisioningLayoutFactory layoutFactory = ProvisioningLayoutFactory.getInstance(UniverseResolver.builder(ufl).build())) {
                final FeaturePackLocation fpl = layoutFactory.addLocal(localPath, false);
                addUniverseArtifacts(fpl, ufl, builder);
                ProvisioningConfig config = ProvisioningConfig.builder().addFeaturePackDep(fpl).build();
                try (ProvisioningLayout layout = layoutFactory.newConfigLayout(config)) {
                    FeaturePackLayout fpLayout = layout.getFeaturePack(fpl.getProducer());
                    for (FeaturePackConfig cfg : fpLayout.getSpec().getFeaturePackDeps()) {
                        addUniverseArtifacts(cfg.getLocation(), ufl, builder);
                    }
                    for (FeaturePackConfig cfg : fpLayout.getSpec().getTransitiveDeps()) {
                        addUniverseArtifacts(cfg.getLocation(), ufl, builder);
                    }
                    Path all = fpLayout.getResource(WfConstants.WILDFLY + "/" + WfConstants.ALL_ARTIFACTS_PROPS);
                    if (all == null) {
                        throw new MojoExecutionException("File " + all + " not found in feature-pack");
                    }
                    final Map<String, String> props;
                    try {
                        props = Utils.readProperties(all);
                    } catch (ProvisioningException e) {
                        throw new MojoExecutionException("Failed to read all artifacts file " + all + " from " + fpl.getFPID(), e);
                    }
                    for (String resolved : props.values()) {
                        MavenArtifact art = retrieveArtifactCoords(resolved);
                        artifactResolver.resolve(art);
                        Path artPath = art.getPath();
                        builder.add(artPath);
                    }
                }
            }
        } catch (ProvisioningException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void addUniverseArtifacts(FeaturePackLocation fpl, UniverseFactoryLoader ufl, MavenRepoBuilder builder) throws ProvisioningException {
        if (fpl.hasUniverse()) {
            Universe u = ufl.getUniverse(fpl.getUniverse());
            if (u instanceof MavenUniverse) {
                MavenUniverse mu = (MavenUniverse) u;
                Path universePath = mu.getArtifact().getPath();
                builder.add(universePath);
                for (MavenProducer mp : mu.getProducers()) {
                    builder.add(mp.getArtifact().getPath());
                }
            }
        }
    }

    private static MavenArtifact retrieveArtifactCoords(String resolvedStr) throws MojoExecutionException {
        //For example: org.wildfly.core:wildfly-cli:8.0.1.CR1-SNAPSHOT:client:jar
        ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(resolvedStr, null);
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId(coords.getGroupId());
        artifact.setArtifactId(coords.getArtifactId());
        artifact.setVersion(coords.getVersion());
        artifact.setClassifier(coords.getClassifier());
        artifact.setExtension(coords.getExtension());
        return artifact;
    }
}
