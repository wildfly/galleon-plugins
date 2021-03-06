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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
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
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.Universe;
import org.jboss.galleon.universe.UniverseFactoryLoader;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenChannel;
import org.jboss.galleon.universe.maven.MavenProducer;
import org.jboss.galleon.universe.maven.MavenUniverse;
import org.wildfly.galleon.plugin.ArtifactCoords;
import static org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo.ARTIFACT_LIST_CLASSIFIER;
import static org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo.ARTIFACT_LIST_EXTENSION;
import org.wildfly.maven.plugins.licenses.LicensesFileWriter;
import org.wildfly.maven.plugins.licenses.model.ProjectLicenseInfo;

/**
 * Aggregate all artifact lists (offliners) of a feature-pack dependencies. In
 * addition adds to the list the feature-pack itself and universe artifacts. The
 * resulting list is attached as an artifact to the current project.
 *
 * If output-licenses-file is set, a license file for the contained artifacts is
 * generated.
 *
 * @author jdenise@redhat.com
 */
@Mojo(name = "generate-all-artifacts-list", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class AllArtifactListGeneratorMojo extends AbstractMojo {

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

    @Parameter(alias = "offline", defaultValue = "false")
    private boolean offline;

    @Parameter(alias = "extra-artifacts", readonly = false, required = false)
    private List<ArtifactItem> extraArtifacts = Collections.emptyList();

    @Parameter(alias = "output-licenses-file", readonly = false, required = false)
    private String licensesFile;

    @Parameter(alias = "excluded-licenses-versions", readonly = false, required = false)
    private String excludedVersions;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final MavenProjectArtifactVersions projectArtifacts = MavenProjectArtifactVersions.getInstance(project);
        DefaultRepositorySystemSession noWorkspaceSession = new DefaultRepositorySystemSession(repoSession);
        noWorkspaceSession.setWorkspaceReader(null);
        MavenArtifactRepositoryManager artifactResolver = offline ? new MavenArtifactRepositoryManager(repoSystem, noWorkspaceSession)
                : new MavenArtifactRepositoryManager(repoSystem, noWorkspaceSession, repositories);
        ArtifactListMerger builder = new ArtifactListMerger(artifactResolver, repoSession.getLocalRepository().getBasedir().toPath(), getLog());
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
            Path path = builder.add(coords);

            addExtraArtifacts(builder, projectArtifacts);

            final FeaturePackLocation fpl = FeaturePackLocation.fromString(fpGroupId + ":" + fpArtifactId + ":" + fpVersion);
            FeaturePackSpec spec = FeaturePackDescriber.readSpec(path);
            for (FeaturePackConfig cfg : spec.getTransitiveDeps()) {
                addFeaturePackContent(cfg.getLocation(), ufl, builder);
            }
            for (FeaturePackConfig cfg : spec.getFeaturePackDeps()) {
                addFeaturePackContent(cfg.getLocation(), ufl, builder);
            }
            addFeaturePackContent(fpl, ufl, builder);
            Path targetDir = Paths.get(project.getBuild().getDirectory());
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            final Path target = targetDir.resolve(fpArtifactId + '-'
                    + fpVersion + "-all-artifacts-list." + ARTIFACT_LIST_EXTENSION);
            Files.write(target, builder.build().getBytes());

            generateLicenses(builder);

            projectHelper.attachArtifact(project, ARTIFACT_LIST_EXTENSION, ARTIFACT_LIST_CLASSIFIER, target.toFile());
        } catch (IOException | ArtifactDescriptorException | ProvisioningException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void generateLicenses(ArtifactListBuilder builder) throws MojoExecutionException, ProvisioningException {
        if (licensesFile == null) {
            return;
        }
        List<ProjectLicenseInfo> dependencies = new ArrayList<>();
        for (String p : builder.getMap().keySet()) {
            if (p.endsWith(".pom")) {
                ArtifactCoords coords = toCoords(Paths.get(p), "pom");
                if (excludedVersions == null || !coords.getVersion().matches(excludedVersions)) {
                    ProjectBuildingRequest req = session.getProjectBuildingRequest();
                    Path resolvedPath = builder.resolveArtifact(coords);
                    ProjectBuildingResult res;
                    try {
                        res = mavenProjectBuilder.build(resolvedPath.toFile(), req);
                    } catch (ProjectBuildingException ex) {
                        getLog().warn("Exception building project for " + p + ", skipping license generation", ex);
                        continue;
                    }
                    dependencies.add(createDependencyProject(res.getProject()));
                }
            }
        }
        LicensesFileWriter fw = new LicensesFileWriter();
        try {
            fw.writeLicenseSummary(dependencies, new File(licensesFile));
        } catch (TransformerException | ParserConfigurationException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static ArtifactCoords toCoords(Path path, String extension) {
        Path version = path.getParent();
        Path artifactId = version.getParent();
        Path groupId = artifactId.getParent();
        String grpId = groupId.toString().startsWith("/") ? groupId.toString().substring(1) : groupId.toString();
        ArtifactCoords coords = new ArtifactCoords(grpId.replaceAll("/", "."),
                artifactId.getFileName().toString(), version.getFileName().toString(), null, extension);
        return coords;
    }

    private ProjectLicenseInfo createDependencyProject(MavenProject depMavenProject) {
        ProjectLicenseInfo dependencyProject
                = new ProjectLicenseInfo(depMavenProject.getGroupId(), depMavenProject.getArtifactId(),
                        depMavenProject.getVersion());
        List<?> licenses = depMavenProject.getLicenses();
        getLog().debug("Adding licenses for " + depMavenProject.getGroupId() + ":"
                + depMavenProject.getArtifactId() + ":" + depMavenProject.getVersion());
        for (Object license : licenses) {
            dependencyProject.addLicense((License) license);
        }
        return dependencyProject;
    }

    private void addExtraArtifacts(ArtifactListMerger builder, MavenProjectArtifactVersions projectArtifacts) throws MojoExecutionException {
        for (ArtifactItem art : extraArtifacts) {
            if (art.getGroupId() == null) {
                throw new MojoExecutionException("GroupId can't be null");
            }
            if (art.getArtifactId() == null) {
                throw new MojoExecutionException("ArtifactId can't be null");
            }
            String ext = art.getType() == null ? "jar" : art.getType();
            String version = art.getVersion();
            if (version == null) {
                String coords = projectArtifacts.getVersion(art.getGroupId() + ":" + art.getArtifactId());
                if (coords != null) {
                    ArtifactCoords c = ArtifactCoordsUtil.fromJBossModules(coords, null);
                    if (c != null) {
                        version = c.getVersion();
                    }
                }
            }
            if (version == null) {
                throw new MojoExecutionException("Version for " + art.getGroupId() + ":" + art.getArtifactId() + " has not been found.");
            }
            try {
                addArtifact(builder, new ArtifactCoords(art.getGroupId(), art.getArtifactId(), version, art.getClassifier(), ext));
            } catch (Exception ex) {
                throw new MojoExecutionException(ex.getMessage(), ex);
            }

        }
    }

    private void addArtifact(ArtifactListMerger builder, ArtifactCoords coords) throws ProvisioningException,
            ArtifactDescriptorException, IOException, DependencyCollectionException {
        CollectRequest request = new CollectRequest();
        request.setRoot(new Dependency(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getExtension(), coords.getVersion()), JavaScopes.RUNTIME));
        CollectResult res = repoSystem.collectDependencies(repoSession, request);
        addDepNode(builder, res.getRoot());
    }

    private void addDepNode(ArtifactListMerger builder, DependencyNode n) throws ProvisioningException, ArtifactDescriptorException, IOException {
        Dependency d = n.getDependency();
        Artifact a = d.getArtifact();
        if (!("provided".equals(d.getScope())) && !("test".equals(d.getScope())) && !("system".equals(d.getScope()))) {
            builder.add(new ArtifactCoords(a.getGroupId(), a.getArtifactId(),
                    a.getVersion(), a.getClassifier(), a.getExtension()));
            for (DependencyNode dn : n.getChildren()) {
                addDepNode(builder, dn);
            }
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
            Universe<?> u = ufl.getUniverse(fpl.getUniverse());
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
            Universe<?> u = ufl.getUniverse(fpl.getUniverse());
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
