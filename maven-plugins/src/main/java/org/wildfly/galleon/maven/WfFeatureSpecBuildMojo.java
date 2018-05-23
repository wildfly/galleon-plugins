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
import java.util.Properties;
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
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.IoUtils;
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
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
@Mojo(name = "generate-feature-specs", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class WfFeatureSpecBuildMojo extends AbstractMojo {

    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    private static final String MODULES = "modules";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(alias = "output-dir", required = true)
    private File outputDirectory;

    @Parameter(alias = "feature-packs", required = false)
    private List<ArtifactItem> featurePacks;

    @Parameter(alias = "external-artifacts", required = false)
    private List<ExternalArtifact> externalArtifacts;

    @Parameter(alias = "standalone-extensions", required = true)
    private List<String> standaloneExtensions;

    @Parameter(alias = "domain-extensions", required = true)
    private List<String> domainExtensions;

    @Parameter(alias = "host-extensions", required = true)
    private List<String> hostExtensions;

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
        Properties props = new Properties();
        int specsTotal = -1;
        try {
            modulesDir = Files.createTempDirectory(MODULES);
            wildflyDir = Files.createTempDirectory("wildfly-specs-dist");
            specsTotal = doExecute(wildflyDir, modulesDir);
        } catch (RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (IOException | MavenFilteringException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        } finally {
            clearXMLConfiguration(props);
            IoUtils.recursiveDelete(modulesDir);

            if(getLog().isDebugEnabled() && specsTotal >= 0) {
                final long totalTime = System.currentTimeMillis() - startTime;
                final long secs = totalTime / 1000;
                debug("Generated " + specsTotal + " feature specs in " + secs + "." + (totalTime - secs * 1000) + " secs");
            }
        }
    }

    private int doExecute(Path wildflyDir, Path modulesDir) throws MojoExecutionException, MojoFailureException, MavenFilteringException, IOException {

        Files.createDirectories(wildflyDir.resolve("standalone").resolve("configuration"));
        Files.createDirectories(wildflyDir.resolve("domain").resolve("configuration"));
        Files.createDirectories(wildflyDir.resolve("bin"));
        Files.createFile(wildflyDir.resolve("bin").resolve("jboss-cli-logging.properties"));
        copyJbossModule(wildflyDir);

        final List<Artifact> featurePackArtifacts = new ArrayList<>();
        final Set<String> inheritedFeatures = getInheritedFeatures(modulesDir, featurePackArtifacts);
        final Map<String, Artifact> buildArtifacts = collectBuildArtifacts(modulesDir, featurePackArtifacts);

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
        Artifact artifact = project.getPluginArtifactMap().get("org.wildfly.galleon-plugins:wildfly-galleon-maven-plugins");
        specGenCp[0] = resolveArtifact(artifact.getGroupId(), "wildfly-feature-spec-gen", artifact.getVersion(), null, "jar").toURI().toURL();
        artifact = buildArtifacts.get("org.jboss.modules:jboss-modules");
        specGenCp[1] = resolveArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), null, "jar").toURI().toURL();
        artifact = buildArtifacts.get("org.wildfly.core:wildfly-cli");
        specGenCp[2] = resolveArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), "client", "jar").toURI().toURL();

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

    private File resolveArtifact(String groupId, String artifactId, String version, String classifier, String extension) throws MojoExecutionException {
        final ArtifactItem item = new ArtifactItem();
        item.setArtifactId(artifactId);
        item.setGroupId(groupId);
        item.setVersion(version);
        if(classifier != null) {
            item.setClassifier(classifier);
        }
        if(extension != null) {
            item.setExtension(extension);
        }
        return findArtifact(item).getFile();
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
        Files.write(wildfly.resolve("standalone").resolve("configuration").resolve("standalone.xml"), lines);

        lines.clear();
        lines.add("<?xml version='1.0' encoding='UTF-8'?>");
        lines.add("<domain xmlns=\"urn:jboss:domain:6.0\">");
        lines.add("<extensions>");
        for (String extension : domainExtensions) {
            lines.add(String.format("<extension module=\"%s\"/>", extension));
        }
        lines.add("</extensions>");
        lines.add("</domain>");
        Files.write(wildfly.resolve("domain").resolve("configuration").resolve("domain.xml"), lines);

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
        Files.write(wildfly.resolve("domain").resolve("configuration").resolve("host.xml"), lines);
    }

    private Map<String, Artifact> collectBuildArtifacts(Path tmpModules, List<Artifact> featurePackArtifacts)
            throws MojoExecutionException, IOException {
        Map<String, Artifact> artifacts = new HashMap<>();
        for (Artifact artifact : project.getArtifacts()) {
            registerArtifact(artifacts, artifact);
        }
        for (Artifact featurePackArtifact : featurePackArtifacts) {
            prepareArtifacts(artifacts, featurePackArtifact);
        }
        if (externalArtifacts == null || externalArtifacts.isEmpty()) {
            return artifacts;
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
        return artifacts;
    }

    private void registerArtifact(Map<String, Artifact> artifacts , Artifact artifact) {
        final String key = getArtifactKey(artifact);
        debug("Registering %s for key %s", artifact.toString(), key);
        artifacts.putIfAbsent(key, artifact);
    }

    private Set<String> getInheritedFeatures(Path tmpModules, List<Artifact> featurePackArtifacts)
            throws MojoExecutionException, IOException {
        if(featurePacks == null || featurePacks.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> inheritedFeatures = new HashSet<>(500);
        IncludeExcludeFileSelector selector = new IncludeExcludeFileSelector();
        selector.setIncludes(new String[] { "**/**/module/modules/**/*", "features/**" });
        IncludeExcludeFileSelector[] selectors = new IncludeExcludeFileSelector[] { selector };
        for (ArtifactItem fp : featurePacks) {
            final Artifact fpArtifact = findArtifact(fp);
            if (fpArtifact == null) {
                getLog().warn("No artifact was found for " + fp);
                continue;
            }
            featurePackArtifacts.add(fpArtifact);
            File archive = fpArtifact.getFile();
            Path tmpArchive = Files.createTempDirectory(fp.getGroupId() + '_' + fp.getArtifactId() + '_' + fp.getVersion());
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
                unArchiver.setDestDirectory(tmpArchive.toFile());
                unArchiver.extract();
                try (Stream<Path> children = Files.list(tmpArchive.resolve("features"))) {
                    List<String> features = children.map(Path::getFileName).map(Path::toString).collect(Collectors.toList());
                    for (String feature : features) {
                        inheritedFeatures.add(feature);
                    }
                }
                setModules(tmpArchive, tmpModules.resolve(MODULES));
            } catch (NoSuchArchiverException ex) {
                getLog().warn(ex);
            } finally {
                IoUtils.recursiveDelete(tmpArchive);
            }
        }
        return inheritedFeatures;
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
                if (isModule(dir)) {
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

    private boolean isModule(Path dir) {
        return MODULES.equals(dir.getFileName().toString())
                && "module".equals(dir.getParent().getFileName().toString())
                && "wildfly".equals(dir.getParent().getParent().getFileName().toString())
                && "pm".equals(dir.getParent().getParent().getParent().getFileName().toString())
                && "packages".equals(dir.getParent().getParent().getParent().getParent().getParent().getFileName().toString());
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
            if (result != null) {
                return result.getArtifact();
            }
            return null;
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

    private void clearXMLConfiguration(Properties props) {
        clearProperty(props, "javax.xml.parsers.DocumentBuilderFactory");
        clearProperty(props, "javax.xml.parsers.SAXParserFactory");
        clearProperty(props, "javax.xml.transform.TransformerFactory");
        clearProperty(props, "javax.xml.xpath.XPathFactory");
        clearProperty(props, "javax.xml.stream.XMLEventFactory");
        clearProperty(props, "javax.xml.stream.XMLInputFactory");
        clearProperty(props, "javax.xml.stream.XMLOutputFactory");
        clearProperty(props, "javax.xml.datatype.DatatypeFactory");
        clearProperty(props, "javax.xml.validation.SchemaFactory");
        clearProperty(props, "org.xml.sax.driver");
    }

    private void clearProperty(Properties props, String name) {
        if (props.containsKey(name)) {
            System.setProperty(name, props.getProperty(name));
        } else {
            System.clearProperty(name);
        }
    }
}
