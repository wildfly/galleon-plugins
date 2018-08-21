/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
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
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.util.StringUtils;
import org.jboss.galleon.ArtifactCoords.Gav;
import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.Utils;
import org.wildfly.galleon.plugin.WfConstants;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * This plug-in generates WildFly feature specifications.
 * It starts a minimal embedded server with the specified extensions, resolving dependencies via Aether, and gets
 * an export of the meta-model from the server to produce the specifications for Galleon.
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
@Mojo(name = "generate-feature-specs", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class WfFeatureSpecBuildMojo extends AbstractMojo {

    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    private static final String MODULES = "modules";

    private static final Path MODULE_PATH_SEGMENT = Paths.get("pm").resolve("wildfly").resolve("module").resolve(MODULES);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * The directory where the generated specifications are written.
     */
    @Parameter(alias = "output-dir", required = true)
    private File outputDirectory;

    /**
     * The feature-pack build configuration file directory
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * The feature-pack build configuration file.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * List of external artifacts to be added to the embedded server.
     */
    @Parameter(alias = "external-artifacts", required = false)
    private List<ExternalArtifact> externalArtifacts;

    /**
     * List of WildFly extensions for the embedded standalone.
     * Used in the standalone.xml
     */
    @Parameter(alias = "standalone-extensions", required = true)
    private List<String> standaloneExtensions;

    /**
     * List of WildFly extensions for the embedded domain.
     * Used in the domain.xml.
     */
    @Parameter(alias = "domain-extensions")
    private List<String> domainExtensions;

    /**
     * List of WildFly extensions for the embedded host.
     * Used in the host.xml.
     */
    @Parameter(alias = "host-extensions")
    private List<String> hostExtensions;

    /**
     * Whether to launch the embedded server to read feature descriptions in a separate process
     */
    @Parameter(alias = "fork-embedded", required = false)
    private boolean forkEmbedded;

    @Component
    private ArchiverManager archiverManager;

    @Component
    private RepositorySystem repoSystem;

    @Component
    private ArtifactResolver artifactResolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final long startTime = System.currentTimeMillis();
        Path modulesDir = null;
        Path wildflyDir = null;
        int specsTotal = -1;
        try {
            modulesDir = Files.createTempDirectory(MODULES);
            wildflyDir = Files.createTempDirectory("wf-specs-dist");
            specsTotal = doExecute(wildflyDir, modulesDir);
        } catch (RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (IOException | MavenFilteringException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        } finally {
            if(modulesDir != null) {
                IoUtils.recursiveDelete(modulesDir);
            }
            if(wildflyDir != null) {
                IoUtils.recursiveDelete(wildflyDir);
            }
            if(getLog().isDebugEnabled() && specsTotal >= 0) {
                final long totalTime = System.currentTimeMillis() - startTime;
                final long secs = totalTime / 1000;
                debug("Generated " + specsTotal + " feature specs in " + secs + "." + (totalTime - secs * 1000) + " secs");
            }
        }
    }

    private int doExecute(Path wildflyDir, Path modulesDir) throws MojoExecutionException, MojoFailureException, MavenFilteringException, IOException {

        Files.createDirectories(wildflyDir.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION));
        Files.createDirectories(wildflyDir.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION));
        Files.createDirectories(wildflyDir.resolve("bin"));
        Files.createFile(wildflyDir.resolve("bin").resolve("jboss-cli-logging.properties"));
        copyJbossModule(wildflyDir);

        Map<String, Artifact> buildArtifacts = new HashMap<>();
        for (Artifact artifact : project.getArtifacts()) {
            registerArtifact(buildArtifacts, artifact);
        }

        final Set<String> inheritedFeatures = getInheritedFeatures(modulesDir, buildArtifacts);
        collectExternalArtifacts(modulesDir, buildArtifacts);

        List<Artifact> hardcodedArtifacts = new ArrayList<>(); // this one includes also the hardcoded artifact versions into module.xml
        ModuleXmlVersionResolver.filterAndConvertModules(modulesDir, wildflyDir.resolve(MODULES), buildArtifacts, hardcodedArtifacts, getLog());
        final Path modulesTemplates = Paths.get(project.getBuild().getDirectory()).resolve("resources").resolve(MODULES);
        if (Files.exists(modulesTemplates)) {
            ModuleXmlVersionResolver.filterAndConvertModules(modulesTemplates, wildflyDir.resolve(MODULES), buildArtifacts, hardcodedArtifacts, getLog());
        }
        addBasicConfigs(wildflyDir);

        for(Artifact art : hardcodedArtifacts) {
            findArtifact(art);
        }

        final URL[] specGenCp = new URL[3];
        specGenCp[0] = resolveArtifact(buildArtifacts, "org.wildfly.galleon-plugins", "wildfly-feature-spec-gen", null).toURI().toURL();
        specGenCp[1] = resolveArtifact(buildArtifacts, "org.jboss.modules", "jboss-modules", null).toURI().toURL();
        specGenCp[2] = resolveArtifact(buildArtifacts, "org.wildfly.core", "wildfly-cli", "client").toURI().toURL();

        final String originalMavenRepoLocal = System.getProperty(MAVEN_REPO_LOCAL);
        System.setProperty(MAVEN_REPO_LOCAL, session.getSettings().getLocalRepository());
        debug("Generating feature specs using local maven repo %s", System.getProperty(MAVEN_REPO_LOCAL));
        try {
            return FeatureSpecGeneratorInvoker.generateSpecs(wildflyDir, inheritedFeatures, outputDirectory.toPath(), specGenCp, forkEmbedded, getLog());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Feature spec generator failed", e);
        } finally {
            if(originalMavenRepoLocal == null) {
                System.clearProperty(MAVEN_REPO_LOCAL);
            } else {
                System.setProperty(MAVEN_REPO_LOCAL, originalMavenRepoLocal);
            }
        }
    }

    private File resolveArtifact(Map<String, Artifact> buildArtifacts, String groupId, String artifactId, String classifier) throws MojoExecutionException {
        Artifact artifact = buildArtifacts.get(groupId + ':' + artifactId);
        if(artifact == null) {
            throw new MojoExecutionException("Failed to locate " + groupId + ':' + artifactId + " among the project build artifacts");
        }
        if (artifact.getFile() != null &&
                (classifier == null && artifact.getClassifier() == null
                || classifier != null && classifier.equals(artifact.getClassifier()))) {
            return artifact.getFile();
        }
        final ArtifactItem item = new ArtifactItem();
        item.setArtifactId(artifact.getArtifactId());
        item.setGroupId(artifact.getGroupId());
        item.setVersion(artifact.getVersion());
        if(classifier != null && !classifier.isEmpty()) {
            item.setClassifier(classifier);
        }
        artifact = findArtifact(item);
        final File f = artifact == null ? null : artifact.getFile();
        if(f == null) {
            throw new MojoExecutionException("Failed to resolve artifact " + item);
        }
        return f;
    }

    private void addBasicConfigs(final Path wildfly) throws IOException {
        final List<String> lines = new ArrayList<>(standaloneExtensions.size() + 5);
        lines.add("<?xml version='1.0' encoding='UTF-8'?>");
        lines.add("<server xmlns=\"urn:jboss:domain:6.0\">");
        lines.add("<extensions>");
        for (String extension : standaloneExtensions) {
            lines.add(String.format("<extension module=\"%s\"/>", extension));
        }
        lines.add("</extensions>");
        lines.add("</server>");
        Files.write(wildfly.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION).resolve("standalone.xml"), lines);

        lines.clear();
        lines.add("<?xml version='1.0' encoding='UTF-8'?>");
        lines.add("<domain xmlns=\"urn:jboss:domain:6.0\">");
        lines.add("<extensions>");
        for (String extension : domainExtensions) {
            lines.add(String.format("<extension module=\"%s\"/>", extension));
        }
        lines.add("</extensions>");
        lines.add("</domain>");
        Files.write(wildfly.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION).resolve("domain.xml"), lines);

        lines.clear();
        lines.add("<?xml version='1.0' encoding='UTF-8'?>");
        lines.add("<host xmlns=\"urn:jboss:domain:6.0\" name=\"master\">");
        lines.add("<extensions>");
        for (String extension : hostExtensions) {
            lines.add(String.format("<extension module=\"%s\"/>", extension));
        }
        lines.add("</extensions>");
        lines.add("<management>");
        lines.add("<management-interfaces>");
        lines.add("<http-interface security-realm=\"ManagementRealm\">");
        lines.add("<http-upgrade enabled=\"true\"/>");
        lines.add("<socket interface=\"management\" port=\"${jboss.management.http.port:9990}\"/>");
        lines.add("</http-interface>");
        lines.add("</management-interfaces>");

        lines.add("</management>");
        lines.add("<domain-controller>");
        lines.add("<local />");
        lines.add("</domain-controller>");
        lines.add("<interfaces>");
        lines.add("<interface name=\"management\">");
        lines.add("<inet-address value=\"127.0.0.1\"/>");
        lines.add("</interface>");
        lines.add("</interfaces>");
        lines.add("</host>");
        Files.write(wildfly.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION).resolve("host.xml"), lines);
    }

    private void collectExternalArtifacts(Path tmpModules, Map<String, Artifact> artifacts)
            throws MojoExecutionException, IOException {
        if (externalArtifacts == null || externalArtifacts.isEmpty()) {
            return;
        }
        for (ExternalArtifact fp : externalArtifacts) {
            IncludeExcludeFileSelector selector = new IncludeExcludeFileSelector();
            selector.setIncludes(StringUtils.split(fp.getIncludes(), ","));
            selector.setExcludes(StringUtils.split(fp.getExcludes(), ","));
            IncludeExcludeFileSelector[] selectors = new IncludeExcludeFileSelector[] { selector };
            final Artifact fpArtifact = findArtifact(fp.getArtifactItem());
            if (fpArtifact == null) {
                getLog().warn("No artifact was found for " + fp);
                continue;
            }
            prepareArtifacts(artifacts, fpArtifact);
            File archive = fpArtifact.getFile();
            Path target = tmpModules.resolve(MODULES).resolve(fp.getToLocation());
            Files.createDirectories(target);
            try {
                UnArchiver unArchiver;
                try {
                    unArchiver = archiverManager.getUnArchiver(fpArtifact.getType());
                    debug("Found unArchiver by type: %s", unArchiver);
                } catch (NoSuchArchiverException e) {
                    unArchiver = archiverManager.getUnArchiver(archive);
                    debug("Found unArchiver by extension: %s", unArchiver);
                }
                unArchiver.setFileSelectors(selectors);
                unArchiver.setSourceFile(archive);
                unArchiver.setDestDirectory(target.toFile());
                unArchiver.extract();
            } catch (NoSuchArchiverException ex) {
                getLog().warn(ex);
            }
        }
    }

    private void registerArtifact(Map<String, Artifact> artifacts , Artifact artifact) {
        final String key = getArtifactKey(artifact);
        debug("Registering %s for key %s", artifact.toString(), key);
        artifacts.putIfAbsent(key, artifact);
    }

    private Set<String> getInheritedFeatures(Path tmpModules, Map<String, Artifact> artifacts)
            throws MojoExecutionException, IOException {
        final WildFlyFeaturePackBuild buildConfig = Util.loadFeaturePackBuildConfig(configDir, configFile);
        final Map<Gav, FeaturePackDependencySpec> fpDeps = buildConfig.getDependencies();
        if(fpDeps.isEmpty()) {
            return Collections.emptySet();
        }

        final MavenProjectArtifactVersions artifactVersions = MavenProjectArtifactVersions.getInstance(project);

        final Set<String> inheritedFeatures = new HashSet<>(500);
        try(ProvisioningLayoutFactory layoutFactory = ProvisioningLayoutFactory.getInstance()) {
            final ProvisioningConfig.Builder configBuilder = ProvisioningConfig.builder();
            for (Map.Entry<Gav, FeaturePackDependencySpec> entry : fpDeps.entrySet()) {
                Gav depGav = entry.getKey();
                if (depGav.getVersion() == null) {
                    String gavStr = artifactVersions.getVersion(depGav.toString());
                    if (gavStr == null) {
                        throw new MojoExecutionException("Failed resolve artifact version for " + depGav);
                    }
                    depGav = ArtifactCoords.newGav(gavStr);
                }
                final ArtifactItem artifact = new ArtifactItem();
                artifact.setGroupId(depGav.getGroupId());
                artifact.setArtifactId(depGav.getArtifactId());
                artifact.setVersion(depGav.getVersion());
                artifact.setType("zip");
                final Artifact resolved = findArtifact(artifact);
                if(resolved == null) {
                    throw new MojoExecutionException("Failed to resolve feature-pack artifact " + artifact);
                }

                final File f = resolved == null ? null : resolved.getFile();
                if(f == null) {
                    throw new MojoExecutionException("Failed to resolve feature-pack artifact " + artifact);
                }
                final Path p = f.toPath();
                final FeaturePackLocation fpl = layoutFactory.addLocal(p, false);
                final FeaturePackConfig depConfig = entry.getValue().getTarget();
                configBuilder.addFeaturePackDep(depConfig.isTransitive() ? FeaturePackConfig.transitiveBuilder(fpl).init(depConfig).build() : FeaturePackConfig.builder(fpl).init(depConfig).build());
            }
            try(ProvisioningLayout<FeaturePackLayout> configLayout = layoutFactory.newConfigLayout(configBuilder.build())) {
                final Path modulesDir = tmpModules.resolve(MODULES);
                for(FeaturePackLayout fp : configLayout.getOrderedFeaturePacks()) {
                    addFeatureSpecs(artifactVersions, fp, inheritedFeatures, modulesDir, artifacts);
                }
            }
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to initialize provisioning layout for the feature-pack dependencies", e);
        }
        return inheritedFeatures;
    }

    private void addFeatureSpecs(MavenProjectArtifactVersions artifactVersions, FeaturePackLayout fp, Set<String> featureSpecs, Path modulesDir, Map<String, Artifact> artifacts) throws MojoExecutionException, IOException {

        final Path fpDir = fp.getDir();
        Path p = fpDir.resolve("features");
        if(Files.exists(p)) {
            try (Stream<Path> children = Files.list(p)) {
                final List<String> features = children.map(Path::getFileName).map(Path::toString).collect(Collectors.toList());
                for (String feature : features) {
                    featureSpecs.add(feature);
                }
            }
        }

        p = fpDir.resolve("packages");
        if(Files.exists(p)) {
            setModules(p, modulesDir);
            p = fpDir.resolve("resources/wildfly/artifact-versions.properties");
            if(Files.exists(p)) {
                final Map<String, String> props;
                try {
                    props = Utils.readProperties(p);
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to read artifact versions file " + p + " from " + fp.getFPID(), e);
                }
                for(String v : props.values()) {
                    final ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(v, "jar");
                    ArtifactItem item = new ArtifactItem();
                    item.setGroupId(coords.getGroupId());
                    item.setArtifactId(coords.getArtifactId());
                    item.setVersion(coords.getVersion());
                    item.setClassifier(coords.getClassifier());
                    item.setType(coords.getExtension());
                    final Artifact resolvedItem = findArtifact(item);
                    registerArtifact(artifacts, resolvedItem);
                }
            }
        }
    }

    private void copyJbossModule(Path wildfly) throws IOException, MojoExecutionException {
        for (org.apache.maven.model.Dependency dep : project.getDependencyManagement().getDependencies()) {
            if ("org.jboss.modules".equals(dep.getGroupId()) && "jboss-modules".equals(dep.getArtifactId())) {
                ArtifactItem jbossModule = new ArtifactItem();
                jbossModule.setArtifactId(dep.getArtifactId());
                jbossModule.setGroupId(dep.getGroupId());
                jbossModule.setVersion(dep.getVersion());
                jbossModule.setType(dep.getType());
                jbossModule.setClassifier(dep.getClassifier());
                File jbossModuleJar = findArtifact(jbossModule).getFile();
                debug("Copying %s to %s", jbossModuleJar.toPath(), wildfly.resolve("jboss-modules.jar"));
                Files.copy(jbossModuleJar.toPath(), wildfly.resolve("jboss-modules.jar"));
            }
        }
    }

    private void setModules(Path fpDirectory, Path moduleDir) throws IOException {
        Files.walkFileTree(fpDirectory, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(MODULE_PATH_SEGMENT)) {
                    debug("Copying %s to %s", dir, moduleDir);
                    IoUtils.copy(dir, moduleDir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Artifact findArtifact(ArtifactItem artifact) throws MojoExecutionException {
        resolveVersion(artifact);
        try {
            ProjectBuildingRequest buildingRequest
                    = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setLocalRepository(session.getLocalRepository());
            buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());
            debug("Resolving artifact %s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            final ArtifactResult result = artifactResolver.resolveArtifact(buildingRequest, artifact);
            return result == null ? null : result.getArtifact();
        } catch (ArtifactResolverException e) {
            throw new MojoExecutionException("Couldn't resolve artifact: " + e.getMessage(), e);
        }
    }

    private Artifact findArtifact(Artifact artifact) throws MojoExecutionException {
        try {
            ProjectBuildingRequest buildingRequest
                    = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setLocalRepository(session.getLocalRepository());
            buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());
            debug("Resolving artifact %s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            ArtifactResult result = artifactResolver.resolveArtifact(buildingRequest, artifact);
            if (result != null) {
                return result.getArtifact();
            }
            return artifact;
        } catch (ArtifactResolverException e) {
            throw new MojoExecutionException("Couldn't resolve artifact: " + e.getMessage(), e);
        }
    }

    private String getArtifactKey(Artifact artifact) {
        final StringBuilder buf = new StringBuilder(artifact.getGroupId()).append(':').
                append(artifact.getArtifactId());
        final String classifier = artifact.getClassifier();
        if (classifier != null && !classifier.isEmpty()) {
            buf.append("::").append(classifier);
        }
        return buf.toString();
    }

    private void resolveVersion(ArtifactItem artifact) {
        if(artifact.getVersion() == null) {
            Artifact managedArtifact = this.project.getManagedVersionMap().get(artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + artifact.getType());
            if(managedArtifact != null) {
                artifact.setVersion(managedArtifact.getVersion());
            }
        }
    }

    private void prepareArtifacts(Map<String, Artifact> artifacts, Artifact artifact) throws MojoExecutionException {
        try {
            CollectRequest request = new CollectRequest();
            request.setRepositories(project.getRemoteProjectRepositories());
            org.eclipse.aether.artifact.Artifact root = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), null, artifact.getVersion(), new DefaultArtifactType(artifact.getType()));
            Dependency dep = new Dependency(root, null);
            request.setRoot(dep);
            CollectResult result = this.repoSystem.collectDependencies(session.getRepositorySession(), request);
            resolveDependency(result.getRoot(), artifacts);
        } catch(DependencyCollectionException e) {
            getLog().error("Couldn't download artifact: " + e.getMessage(), e);
        }
    }

    private void resolveDependency(DependencyNode node, Map<String, Artifact> artifacts ) {
        org.eclipse.aether.artifact.Artifact aetherArtifact = getArtifact(node.getArtifact());
        if(aetherArtifact == null) {
            return;
        }
        registerArtifact(artifacts, RepositoryUtils.toArtifact(aetherArtifact));
        for(DependencyNode child : node.getChildren()) {
            resolveDependency(child, artifacts);
        }
    }

    private org.eclipse.aether.artifact.Artifact getArtifact(org.eclipse.aether.artifact.Artifact artifact) {
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setRepositories(project.getRemoteProjectRepositories());
            request.setArtifact(artifact);
            org.eclipse.aether.resolution.ArtifactResult result = this.repoSystem.resolveArtifact(session.getRepositorySession(), request);
            return result.getArtifact();
        } catch(ArtifactResolutionException e) {
            getLog().error("Couldn't download artifact: " + e.getMessage(), e);
        }
        return null;
    }

    private void debug(String format, Object... args) {
        final Log log = getLog();
        if (log.isDebugEnabled()) {
            log.debug(String.format(format, args));
        }
    }
}
