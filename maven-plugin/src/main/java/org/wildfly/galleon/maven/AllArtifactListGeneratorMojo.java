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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
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
import org.jboss.galleon.universe.maven.MavenChannel;
import org.jboss.galleon.universe.maven.MavenProducer;
import org.jboss.galleon.universe.maven.MavenUniverse;
import org.wildfly.galleon.plugin.ArtifactCoords;
import static org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo.ARTIFACT_LIST_CLASSIFIER;
import static org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo.ARTIFACT_LIST_EXTENSION;

/**
 * Aggregate all artifact lists (offliners) of a a feature-pack dependencies. In
 * addition adds to the list the feature-pack itself and universe artifacts. The
 * list is deployed to maven at install time.
 *
 * @author jdenise@redhat.com
 */
@Mojo(name = "generate-all-artifacts-list", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class AllArtifactListGeneratorMojo extends AbstractMojo {

    @Component
    private MavenProjectHelper projectHelper;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final MavenProjectArtifactVersions projectArtifacts = MavenProjectArtifactVersions.getInstance(project);
        DefaultRepositorySystemSession noWorkspaceSession = new DefaultRepositorySystemSession(repoSession);
        noWorkspaceSession.setWorkspaceReader(null);
        MavenArtifactRepositoryManager artifactResolver = new MavenArtifactRepositoryManager(repoSystem, noWorkspaceSession, repositories);
        ArtifactListMerger builder = new ArtifactListMerger(artifactResolver, repoSession.getLocalRepository().getBasedir().toPath());
        final UniverseFactoryLoader ufl = UniverseFactoryLoader.getInstance().addArtifactResolver(artifactResolver);
        try {
            // Add top level feature-pack itself to offliner.
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
            ArtifactCoords coords = new ArtifactCoords(fpGroupId, fpArtifactId, fpVersion, null, "zip");
            Path localPath = builder.add(coords);

            try (ProvisioningLayoutFactory layoutFactory = ProvisioningLayoutFactory.getInstance(UniverseResolver.builder(ufl).build())) {
                final FeaturePackLocation fpl = layoutFactory.addLocal(localPath, false);
                ProvisioningConfig config = ProvisioningConfig.builder().addFeaturePackDep(fpl).build();
                try (ProvisioningLayout layout = layoutFactory.newConfigLayout(config)) {
                    FeaturePackLayout fpLayout = layout.getFeaturePack(fpl.getProducer());
                    for (FeaturePackConfig cfg : fpLayout.getSpec().getTransitiveDeps()) {
                        addFeaturePackContent(cfg.getLocation(), ufl, builder);
                    }
                    for (FeaturePackConfig cfg : fpLayout.getSpec().getFeaturePackDeps()) {
                        addFeaturePackContent(cfg.getLocation(), ufl, builder);
                    }
                }
                addFeaturePackContent(fpl, ufl, builder);
            }
            Path targetDir = Paths.get(project.getBuild().getDirectory());
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            final Path target = targetDir.resolve(fpArtifactId + '-'
                    + fpVersion + "-all-artifacts-list." + ARTIFACT_LIST_EXTENSION);
            Files.write(target, builder.build().getBytes());
            projectHelper.attachArtifact(project, ARTIFACT_LIST_EXTENSION, ARTIFACT_LIST_CLASSIFIER, target.toFile());
        } catch (IOException | ArtifactDescriptorException | ProvisioningException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void addFeaturePackContent(FeaturePackLocation fpl, UniverseFactoryLoader ufl, ArtifactListMerger builder)
            throws ProvisioningException, ArtifactDescriptorException, IOException {
        addUniverseArtifacts(fpl, ufl, builder);
        addOffliner(fpl, ufl, builder);
    }
    private void addOffliner(FeaturePackLocation fpl, UniverseFactoryLoader ufl, ArtifactListMerger builder) throws ProvisioningException, IOException {
        ArtifactCoords coords = null;
        if (fpl.isMavenCoordinates()) {
            String producer = fpl.getProducerName();
            ArtifactCoords fpCoords = ArtifactCoords.fromString(producer, "zip");
            coords = new ArtifactCoords(fpCoords.getGroupId(), fpCoords.getArtifactId(),
                    fpl.getBuild(), ARTIFACT_LIST_CLASSIFIER, ARTIFACT_LIST_EXTENSION);
        } else {
            Universe u = ufl.getUniverse(fpl.getUniverse());
            if (u instanceof MavenUniverse) {
                MavenUniverse mu = (MavenUniverse) u;
                MavenChannel channel = mu.getProducer(fpl.getProducerName()).getChannel(fpl.getChannelName());
                coords = new ArtifactCoords(channel.getFeaturePackGroupId(), channel.getFeaturePackArtifactId(),
                        fpl.getBuild(), ARTIFACT_LIST_CLASSIFIER, ARTIFACT_LIST_EXTENSION);
            }
        }
        if (coords != null) {
            builder.addOffliner(coords);
        }
    }

    private void addUniverseArtifacts(FeaturePackLocation fpl, UniverseFactoryLoader ufl, ArtifactListMerger builder) throws ProvisioningException, ArtifactDescriptorException, IOException {
        if (fpl.hasUniverse()) {
            Universe u = ufl.getUniverse(fpl.getUniverse());
            if (u instanceof MavenUniverse) {
                MavenUniverse mu = (MavenUniverse) u;
                MavenArtifact universeArtifact = mu.getArtifact();
                builder.add(new ArtifactCoords(universeArtifact.getGroupId(), universeArtifact.getArtifactId(), universeArtifact.getVersion(),
                        universeArtifact.getClassifier(), universeArtifact.getExtension()));
                for (MavenProducer mp : mu.getProducers()) {
                    MavenArtifact producerArt = mp.getArtifact();
                    builder.add(new ArtifactCoords(producerArt.getGroupId(), producerArt.getArtifactId(), producerArt.getVersion(),
                            producerArt.getClassifier(), producerArt.getExtension()));
                }
            }
        }
    }
}
