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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.UniverseFactoryLoader;
import org.jboss.galleon.universe.UniverseResolver;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.Utils;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WildFlyPackageTask;
import org.wildfly.galleon.plugin.WildFlyPackageTasks;
import org.wildfly.galleon.plugin.ArtifactCoords.Gav;
import org.wildfly.galleon.plugin.config.CopyArtifact;
import org.wildfly.galleon.plugin.config.WildFlyPackageTasksParser;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureSpecGeneratorInvoker {

    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    private static final String MODULES = "modules";

    public static final String MODULE_PATH_SEGMENT;
    private static final String TASKS_XML_PATH_END;

    static {
        final String pmWf = WfConstants.PM + File.separator + WfConstants.WILDFLY;
        MODULE_PATH_SEGMENT = pmWf + File.separator + WfConstants.MODULE + File.separator + MODULES;
        TASKS_XML_PATH_END = pmWf + File.separator + WfConstants.TASKS_XML;
    }

    private MavenProject project;
    private MavenSession session;
    private List<RemoteRepository> repositories;
    private RepositorySystem repoSystem;
    private ArtifactResolver artifactResolver;
    private WildFlyFeaturePackBuild buildConfig;
    private Log log;

    private File featureSpecsOutput;
    private boolean forkEmbedded;
    private Path wildflyHome;
    private Path moduleTemplatesDir;

    private Map<String, Artifact> mergedArtifacts = new HashMap<>();
    private Map<String, Map<String, Artifact>> moduleTemplates = new HashMap<>();

    private Map<String, Path> inheritedFeatureSpecs = Collections.emptyMap();
    private Set<String> standaloneExtensions = Collections.emptySet();
    private Set<String> domainExtensions = Collections.emptySet();
    private Set<String> hostExtensions = Collections.emptySet();
    private List<Path> layersConfs = Collections.emptyList();

    private WildFlyPackageTasksParser tasksParser;
    private ProvisioningLayoutFactory layoutFactory;
    private ProvisioningLayout<FeaturePackLayout> configLayout;
    private final JakartaTransformation jakartaTransformation;
    FeatureSpecGeneratorInvoker(WfFeaturePackBuildMojo mojo) throws MojoExecutionException {
        this.project = mojo.project;
        this.session = mojo.session;
        this.repositories = mojo.repositories;
        this.repoSystem = mojo.repoSystem;
        this.artifactResolver = mojo.artifactResolver;
        this.buildConfig = mojo.getBuildConfig();
        this.featureSpecsOutput = mojo.featureSpecsOutput;
        this.forkEmbedded = mojo.forkEmbedded;
        this.wildflyHome = mojo.wildflyHome.toPath();
        this.moduleTemplatesDir = mojo.moduleTemplatesDir.toPath();
        this.log = mojo.getLog();
        this.jakartaTransformation = mojo.getJakartaTransformation();
    }

    public void execute() throws MojoExecutionException, MojoFailureException {

        final long startTime = System.currentTimeMillis();
        int specsTotal = -1;
        try {
            IoUtils.recursiveDelete(moduleTemplatesDir);
            Files.createDirectories(moduleTemplatesDir);
            IoUtils.recursiveDelete(wildflyHome);
            Files.createDirectories(wildflyHome);
            specsTotal = doExecute();
        } catch (RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (IOException | MavenFilteringException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        } finally {
            if(configLayout != null) {
                configLayout.close();
            }
            if(layoutFactory != null) {
                layoutFactory.close();
            }
            if(log.isDebugEnabled() && specsTotal >= 0) {
                final long totalTime = System.currentTimeMillis() - startTime;
                final long secs = totalTime / 1000;
                debug("Generated " + specsTotal + " feature specs in " + secs + "." + (totalTime - secs * 1000) + " secs");
            }
        }
    }

    private int doExecute() throws MojoExecutionException, MojoFailureException, MavenFilteringException, IOException {

        Files.createDirectories(wildflyHome.resolve("bin"));
        Files.createFile(wildflyHome.resolve("bin").resolve("jboss-cli-logging.properties"));

        if(buildConfig.hasStandaloneExtensions()) {
            Files.createDirectories(wildflyHome.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION));
            standaloneExtensions = new HashSet<>(buildConfig.getStandaloneExtensions());
        }
        if(buildConfig.hasDomainExtensions() || buildConfig.hasHostExtensions()) {
            Files.createDirectories(wildflyHome.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION));
            domainExtensions = new HashSet<>(buildConfig.getDomainExtensions());
            hostExtensions = new HashSet<>(buildConfig.getHostExtensions());
        }

        processFeaturePackDeps(buildConfig);
        for (Artifact artifact : MavenProjectArtifactVersions.getFilteredArtifacts(project, buildConfig)) {
            registerArtifact(artifact, null);
        }

        final Path projectResources = Paths.get(project.getBuild().getDirectory()).resolve("resources");
        final Path packagesDir = projectResources.resolve(Constants.PACKAGES);
        if (Files.exists(packagesDir)) {
            findAndCopyModules(packagesDir, mergedArtifacts);
            // layers.conf
            Path fpLayersConf = packagesDir.resolve(WfConstants.LAYERS_CONF);
            if (Files.exists(fpLayersConf)) {
                fpLayersConf = fpLayersConf.resolve(WfConstants.CONTENT).resolve(WfConstants.MODULES)
                        .resolve(WfConstants.LAYERS_CONF);
                if (!Files.exists(fpLayersConf)) {
                    throw new MojoExecutionException("Package " + WfConstants.LAYERS_CONF + " is expected to contain "
                            + WfConstants.MODULES + "/" + WfConstants.LAYERS_CONF + " but it does not");
                }
                layersConfs = CollectionUtils.add(layersConfs, fpLayersConf);
            }
            if (!layersConfs.isEmpty()) {
                try {
                    Utils.mergeLayersConfs(layersConfs, wildflyHome);
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to install layers.conf", e);
                }
            }
        }

        final Path projectModules = projectResources.resolve(MODULES);
        if(Files.exists(projectModules)) {
            copyModules(projectModules, mergedArtifacts);
        }

        if(!moduleTemplates.isEmpty()) {
            final List<Artifact> hardcodedArtifacts = new ArrayList<>(); // this one includes also the hardcoded artifact versions into module.xml
            final Path targetModules = wildflyHome.resolve(MODULES);
            for(Map.Entry<String, Map<String, Artifact>> entry : moduleTemplates.entrySet()) {
                try {
                    ModuleXmlVersionResolver.convertModule(moduleTemplatesDir.resolve(entry.getKey()), targetModules.resolve(entry.getKey()), entry.getValue(), hardcodedArtifacts, log);
                } catch (Exception e) {
                    throw new MojoExecutionException("Failed to process " + moduleTemplatesDir.resolve(entry.getKey()), e);
                }
                if (jakartaTransformation.isJakartaTransformEnabled()) {
                    for (Artifact toTransform : entry.getValue().values()) {
                        if (!toTransform.isResolved()) {
                            toTransform = findArtifact(new ArtifactItem(toTransform));
                        }
                        jakartaTransformation.transform(toTransform);
                    }
                }
            }
            for (Artifact art : hardcodedArtifacts) {
                findArtifact(art);
            }
        }

        addBasicConfigs();

        final String originalMavenRepoLocal = System.getProperty(MAVEN_REPO_LOCAL);
        System.setProperty(MAVEN_REPO_LOCAL,
                jakartaTransformation.isJakartaTransformEnabled() ? jakartaTransformation.getJakartaTransformMavenRepo().toAbsolutePath().toString() : session.getSettings().getLocalRepository());
        debug("Generating feature specs using local maven repo %s", System.getProperty(MAVEN_REPO_LOCAL));
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        URLClassLoader newCl = null;
        try {
            if(!forkEmbedded) {
                if (originalCl instanceof URLClassLoader) {
                    newCl = new URLClassLoader(((URLClassLoader) originalCl).getURLs(), originalCl.getParent());
                    Thread.currentThread().setContextClassLoader(newCl);
                } else {
                    log.warn("Embedded server will be launched using the context classloader. Subsequent attempts to launch it using the same classloader may fail.");
                }
            }
            final Class<?> specGenCls = (newCl == null ? originalCl : newCl).loadClass("org.wildfly.galleon.plugin.featurespec.generator.FeatureSpecGenerator");
            final Method specGenMethod = specGenCls.getMethod("generateSpecs");
            return (int) specGenMethod.invoke(specGenCls.getConstructor(String.class, Path.class, Map.class, boolean.class, boolean.class)
                    .newInstance(wildflyHome.toString(), featureSpecsOutput.toPath(), inheritedFeatureSpecs, forkEmbedded, log.isDebugEnabled()));
        } catch(InvocationTargetException e) {
            throw new MojoExecutionException("Feature spec generator failed", e.getCause());
        } catch (Throwable e) {
            throw new MojoExecutionException("Feature spec generator failed", e);
        } finally {
            if(newCl != null) {
                Thread.currentThread().setContextClassLoader(originalCl);
                try {
                    newCl.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(originalMavenRepoLocal == null) {
                System.clearProperty(MAVEN_REPO_LOCAL);
            } else {
                System.setProperty(MAVEN_REPO_LOCAL, originalMavenRepoLocal);
            }
        }
    }

    private void addBasicConfigs() throws IOException {
        final List<String> lines = new ArrayList<>();
        if(!standaloneExtensions.isEmpty()) {
            lines.add("<?xml version='1.0' encoding='UTF-8'?>");
            lines.add("<server xmlns=\"urn:jboss:domain:6.0\">");
            lines.add("<extensions>");
            for (String extension : standaloneExtensions) {
                lines.add(String.format("<extension module=\"%s\"/>", extension));
            }
            lines.add("</extensions>");
            lines.add("</server>");
            Files.write(wildflyHome.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION).resolve("standalone.xml"), lines);
        }

        if (!domainExtensions.isEmpty()) {
            lines.clear();
            lines.add("<?xml version='1.0' encoding='UTF-8'?>");
            lines.add("<domain xmlns=\"urn:jboss:domain:6.0\">");
            lines.add("<extensions>");
            for (String extension : domainExtensions) {
                lines.add(String.format("<extension module=\"%s\"/>", extension));
            }
            lines.add("</extensions>");
            lines.add("</domain>");
            Files.write(wildflyHome.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION).resolve("domain.xml"), lines);
        }

        if(!hostExtensions.isEmpty()) {
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
            lines.add("<http-interface>");
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
            Files.write(wildflyHome.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION).resolve("host.xml"), lines);
        }
    }

    private void copyArtifact(CopyArtifact task, Map<String, Artifact> artifacts) throws IOException {
        final String artifactCoords = task.getArtifact();
        ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(artifactCoords, "jar");
        Artifact artifact = null;
        if(coords.getVersion() == null) {
            artifact = artifacts.get(artifactCoords);
        }
        if (artifact == null) {
            String key = coords.getGroupId() + ":" + coords.getArtifactId();
            if (coords.getClassifier() == null || coords.getClassifier().isEmpty()) {
                artifact = artifacts.get(key);
            } else {
                artifact = artifacts.get(key + "::" + coords.getClassifier());
            }
        }
        if (artifact == null) {
            final ArtifactItem item = new ArtifactItem();
            item.setGroupId(coords.getGroupId());
            item.setArtifactId(coords.getArtifactId());
            item.setVersion(coords.getVersion());
            item.setClassifier(coords.getClassifier());
            item.setType(coords.getExtension());
            try {
                artifact = findArtifact(item);
            } catch (MojoExecutionException e) {
                if(task.isOptional()) {
                    return;
                }
                throw new IOException("Failed to resolve " + coords, e);
            }
            if(artifact == null) {
                if(task.isOptional()) {
                    return;
                }
                throw new IOException("Failed to resolve " + coords);
            }
        }

        String location = task.getToLocation();
        if (!location.isEmpty() && location.charAt(location.length() - 1) == '/') {
            // if the to location ends with a / then it is a directory
            // so we need to append the artifact name
            location += artifact.getFile().getName();
        }
        final Path target = wildflyHome.resolve(location);
        final Path src = artifact.getFile().toPath();
        debug("Copying artifact %s to %s", src, target);

        if(task.isExtract()) {
            Utils.extractArtifact(src, target, task);
        } else {
            IoUtils.copy(src, target);
        }
    }

    private void registerArtifact(Artifact artifact, Map<String, Artifact> artifacts) {
        final String key = getArtifactKey(artifact);
        debug("Registering %s for key %s", artifact.toString(), key);
        if(artifacts != null) {
            artifacts.put(key, artifact);
        }
        mergedArtifacts.put(key, artifact);
    }

    private void initConfigLayout(WildFlyFeaturePackBuild buildConfig, MavenProjectArtifactVersions artifactVersions) throws MojoExecutionException {

        final MavenArtifactRepositoryManager mvnRepo = new MavenArtifactRepositoryManager(repoSystem, session.getRepositorySession(), repositories);
        final UniverseFactoryLoader ufl = UniverseFactoryLoader.getInstance().addArtifactResolver(mvnRepo);

        try {
            this.layoutFactory = ProvisioningLayoutFactory.getInstance(UniverseResolver.builder(ufl).build());
            final ProvisioningConfig.Builder configBuilder = ProvisioningConfig.builder();
            for (Map.Entry<Gav, FeaturePackDependencySpec> entry : buildConfig.getDependencies().entrySet()) {
                ArtifactCoords depCoords = entry.getKey().toArtifactCoords();
                String ext = "zip";
                if (depCoords.getVersion() == null) {
                    final String coordsStr = artifactVersions
                            .getVersion(depCoords.getGroupId() + ':' + depCoords.getArtifactId());
                    if (coordsStr == null) {
                        throw new MojoExecutionException("Failed resolve artifact version for " + depCoords);
                    }
                    depCoords = ArtifactCoordsUtil.fromJBossModules(coordsStr, ext);
                    if (!depCoords.getExtension().equals("pom")) {
                        ext = depCoords.getExtension();
                    }
                }
                final ArtifactItem artifact = new ArtifactItem();
                artifact.setGroupId(depCoords.getGroupId());
                artifact.setArtifactId(depCoords.getArtifactId());
                artifact.setVersion(depCoords.getVersion());
                artifact.setType(ext);
                final Artifact resolved = findArtifact(artifact);
                if (resolved == null) {
                    throw new MojoExecutionException("Failed to resolve feature-pack artifact " + artifact);
                }
                final Path p = resolved.getFile().toPath();
                if (p == null) {
                    throw new MojoExecutionException("Failed to resolve feature-pack artifact path " + artifact);
                }
                final FeaturePackLocation fpl = layoutFactory.addLocal(p, false);
                final FeaturePackConfig depConfig = entry.getValue().getTarget();
                configBuilder.addFeaturePackDep(
                        depConfig.isTransitive() ? FeaturePackConfig.transitiveBuilder(fpl).init(depConfig).build()
                                : FeaturePackConfig.builder(fpl).init(depConfig).build());
            }
            configLayout = layoutFactory.newConfigLayout(configBuilder.build());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to initialize provisioning layout for the feature-pack dependencies", e);
        }
    }

    private void processFeaturePackDeps(WildFlyFeaturePackBuild buildConfig)
            throws MojoExecutionException, IOException {
        final Map<Gav, FeaturePackDependencySpec> fpDeps = buildConfig.getDependencies();
        if(fpDeps.isEmpty()) {
            return;
        }

        final MavenProjectArtifactVersions artifactVersions = MavenProjectArtifactVersions.getInstance(project);
        initConfigLayout(buildConfig, artifactVersions);

        for(FeaturePackLayout fp : configLayout.getOrderedFeaturePacks()) {
            processFeaturePackDep(artifactVersions, fp);
        }

        try {
            layersConfs = Utils.collectLayersConf(configLayout);
        } catch (ProvisioningException e1) {
            throw new MojoExecutionException("Failed to collect layyers.conf files from feature-pack dependencies", e1);
        }
    }

    private void processFeaturePackDep(MavenProjectArtifactVersions artifactVersions, FeaturePackLayout fp) throws MojoExecutionException, IOException {

        final Path fpDir = fp.getDir();
        Path p = fpDir.resolve("features");
        if(Files.exists(p)) {
            if(inheritedFeatureSpecs.isEmpty()) {
                inheritedFeatureSpecs = new HashMap<>(500);
            }
            try(DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for(Path path : stream) {
                    String specName = path.getFileName().toString();
                    if(specName.charAt(specName.length() - 1) == '/') {
                        specName = specName.substring(0, specName.length() - 1);
                    }
                    path = path.resolve(Constants.SPEC_XML);
                    if(!Files.exists(path)) {
                        continue;
                    }
                    inheritedFeatureSpecs.put(specName, path);
                }
            }
        }

        p = fpDir.resolve("packages");
        if(Files.exists(p)) {
            Map<String, Artifact> fpArtifacts = Collections.emptyMap();
            final Path versionProps = fpDir.resolve("resources/wildfly/artifact-versions.properties");
            if(Files.exists(versionProps)) {
                final Map<String, String> props;
                try {
                    props = Utils.readProperties(versionProps);
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to read artifact versions file " + versionProps + " from " + fp.getFPID(), e);
                }
                fpArtifacts = new HashMap<>(props.size());
                for(String v : props.values()) {
                    final ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(v, "jar");
                    ArtifactItem item = new ArtifactItem();
                    item.setGroupId(coords.getGroupId());
                    item.setArtifactId(coords.getArtifactId());
                    item.setVersion(coords.getVersion());
                    item.setClassifier(coords.getClassifier());
                    item.setType(coords.getExtension());
                    try {
                        registerArtifact(findArtifact(item), fpArtifacts);
                    } catch (MojoExecutionException e) {
                        throw new MojoExecutionException("Failed to resolve artifact " + coords + " as a dependency of " + fp.getFPID() + " (persisted as " + v + ")", e);
                    }
                }
            }
            findAndCopyModules(p, fpArtifacts);
        }

        if (!standaloneExtensions.isEmpty()) {
            try {
                p = fp.getResource(WfConstants.WILDFLY, WfConstants.EXTENSIONS_STANDALONE);
            } catch (ProvisioningDescriptionException e) {
                throw new MojoExecutionException("Failed to resolve extensions", e);
            }
            if (Files.exists(p)) {
                try (BufferedReader reader = Files.newBufferedReader(p)) {
                    String line = reader.readLine();
                    while (line != null) {
                        standaloneExtensions.add(line);
                        line = reader.readLine();
                    }
                }
            }
        }

        if(!domainExtensions.isEmpty() || !hostExtensions.isEmpty()) {
            try {
                p = fp.getResource(WfConstants.WILDFLY, WfConstants.EXTENSIONS_DOMAIN);
            } catch (ProvisioningDescriptionException e) {
                throw new MojoExecutionException("Failed to resolve extensions", e);
            }
            if (Files.exists(p)) {
                try (BufferedReader reader = Files.newBufferedReader(p)) {
                    String line = reader.readLine();
                    while (line != null) {
                        domainExtensions.add(line);
                        line = reader.readLine();
                    }
                }
            }

            try {
                p = fp.getResource(WfConstants.WILDFLY, WfConstants.EXTENSIONS_HOST);
            } catch (ProvisioningDescriptionException e) {
                throw new MojoExecutionException("Failed to resolve extensions", e);
            }
            if (Files.exists(p)) {
                try (BufferedReader reader = Files.newBufferedReader(p)) {
                    String line = reader.readLine();
                    while (line != null) {
                        hostExtensions.add(line);
                        line = reader.readLine();
                    }
                }
            }
        }
    }

    private void findAndCopyModules(Path fpDirectory, Map<String, Artifact> fpArtifacts) throws IOException {
        Files.walkFileTree(fpDirectory, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(MODULE_PATH_SEGMENT)) {
                    debug("Copying %s to %s", dir, moduleTemplatesDir);
                    copyModules(dir, fpArtifacts);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.endsWith(TASKS_XML_PATH_END)) {
                    processPackageTasks(file, fpArtifacts);
                } else {
                    if (file.endsWith(WfConstants.LAYERS_CONF)) {
                        layersConfs = CollectionUtils.add(layersConfs, file);
                        try {
                            Utils.mergeLayersConfs(layersConfs, wildflyHome);
                        } catch (ProvisioningException e) {
                            throw new RuntimeException("Failed to install layers.conf", e);
                        }
                    }
                }
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

    private void copyModules(final Path source, Map<String, Artifact> fpArtifacts) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                        final String relativePath = source.relativize(dir).toString();
                        final Path targetDir = moduleTemplatesDir.resolve(relativePath);
                        Files.createDirectories(targetDir.getParent());
                        try {
                            Files.copy(dir, targetDir);
                        } catch (FileAlreadyExistsException e) {
                             if (!Files.isDirectory(targetDir)) {
                                 throw e;
                             }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                        if (WfConstants.MODULE_XML.equals(file.getFileName().toString())) {
                            final String relativePath = source.relativize(file).toString();
                            moduleTemplates.put(relativePath, fpArtifacts);
                            Files.copy(file, moduleTemplatesDir.resolve(relativePath), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            final Path target = wildflyHome.resolve(MODULES).resolve(source.relativize(file).toString());
                            Files.createDirectories(target.getParent());
                            Files.copy(file, target);
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private void processPackageTasks(Path file, Map<String, Artifact> artifacts) throws IOException {
        if(tasksParser == null) {
            tasksParser = new WildFlyPackageTasksParser();
        }
        final WildFlyPackageTasks tasks;
        try(InputStream input = Files.newInputStream(file)) {
            tasks = tasksParser.parse(input);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to parse " + file, e);
        }
        for(WildFlyPackageTask task : tasks.getTasks()) {
            if(!task.getClass().equals(CopyArtifact.class)) {
                continue;
            }
            copyArtifact((CopyArtifact) task, artifacts);
        }
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
            Artifact retVal = result != null ? result.getArtifact() : artifact;
            if (jakartaTransformation.isJakartaTransformEnabled()) {
                jakartaTransformation.transform(retVal);
            }
            return retVal;
        } catch (ArtifactResolverException | IOException e) {
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

    private void debug(String format, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(String.format(format, args));
        }
    }
}
