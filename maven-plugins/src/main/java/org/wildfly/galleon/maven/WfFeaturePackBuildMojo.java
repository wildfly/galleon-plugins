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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

import nu.xom.ParsingException;

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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.FeaturePackLayoutDescriber;
import org.jboss.galleon.maven.plugin.FpMavenErrors;
import org.jboss.galleon.maven.plugin.util.MavenPluginUtil;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.PathFilter;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.xml.FeaturePackXmlWriter;
import org.jboss.galleon.xml.PackageXmlParser;
import org.jboss.galleon.xml.PackageXmlWriter;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.maven.ModuleParseResult.ModuleDependency;

/**
 * This plug-in builds a WildFly feature-pack arranging the content by packages.
 * The artifact versions are resolved here. The configuration pieces are copied into
 * the feature-pack resources directory and will be assembled at the provisioning time.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "build-feature-pack", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WfFeaturePackBuildMojo extends AbstractMojo {

    private static Pattern windowsLineEndingPattern = Pattern.compile("(?<!\\r)\\n", Pattern.MULTILINE);
    private static Pattern linuxLineEndingPattern = Pattern.compile("\\r\\n", Pattern.MULTILINE);
    private static PathFilter windowsLineEndingsPathFilter = new PathFilter() {
        @Override
        public boolean accept(Path path) {
            return path.getFileName().toString().endsWith(".bat");
        }};
    private static PathFilter linuxLineEndingsPathFilter = new PathFilter() {
            @Override
            public boolean accept(Path path) {
                final String name = path.getFileName().toString();
                return name.endsWith(".sh") || name.endsWith(".conf");
            }};

    private static boolean isProvided(String module) {
        return module.startsWith("java.") ||
                module.startsWith("jdk.") ||
                module.equals("org.jboss.modules.main");
    }

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * The configuration file used for feature pack.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * The directory the configuration file is located in.
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * A path relative to {@link #configDir} that represents the directory under which of resources such as
     * {@code configuration/standalone/subsystems.xml}, {modules}, {subsystem-templates}, etc.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;


    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    /**
     * The release name
     */
    @Parameter(alias="release-name", defaultValue = "${product.release.name}", required=true)
    private String releaseName;

    /**
     * The release name
     */
    @Parameter(alias="feature-pack-artifact-id", defaultValue = "${project.artifactId}", required=false)
    private String fpArtifactId;

    @Inject
    private MavenPluginUtil mavenPluginUtil;

    private MavenProjectArtifactVersions artifactVersions;

    private WildFlyFeaturePackBuild wfFpConfig;
    private Map<String, FeaturePackLayout> fpDependencies = Collections.emptyMap();
    private Map<String, PackageSpec.Builder> extendedPackages = Collections.emptyMap();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            doExecute();
        } catch(RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        }
    }

    private void doExecute() throws MojoExecutionException, MojoFailureException {
        artifactVersions = MavenProjectArtifactVersions.getInstance(project);

        /* normalize resourcesDir */
        if (!resourcesDir.isEmpty()) {
            switch (resourcesDir.charAt(0)) {
            case '/':
            case '\\':
                break;
            default:
                resourcesDir = "/" + resourcesDir;
                break;
            }
        }
        final Path targetResources = Paths.get(buildName, Constants.RESOURCES);
        final Path specsDir = Paths.get(configDir.getAbsolutePath() + resourcesDir);
        if (Files.exists(specsDir)) {
            try {
                IoUtils.copy(specsDir, targetResources);
            } catch (IOException e1) {
                throw new MojoExecutionException(Errors.copyFile(specsDir, targetResources), e1);
            }
        }

        final Path workDir = Paths.get(buildName, WfConstants.LAYOUT);
        IoUtils.recursiveDelete(workDir);
        final Path fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
        final Path fpPackagesDir = fpDir.resolve(Constants.PACKAGES);

        // feature-pack builder
        final FeaturePackLayout.Builder fpBuilder = FeaturePackLayout.builder(
                FeaturePackSpec.builder(ArtifactCoords.newGav(project.getGroupId(), fpArtifactId, project.getVersion())));

        // feature-pack build config
        try {
            wfFpConfig = Util.loadFeaturePackBuildConfig(getFPConfigFile());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to load feature-pack config file", e);
        }

        for(String defaultPackage : wfFpConfig.getDefaultPackages()) {
            fpBuilder.getSpecBuilder().addDefaultPackage(defaultPackage);
        }

        try {
            processFeaturePackDependencies(fpBuilder.getSpecBuilder());
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to process dependencies", e);
        }

        final Path srcModulesDir = targetResources.resolve(WfConstants.MODULES).resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS).resolve(WfConstants.BASE);
        if (Files.exists(srcModulesDir)) {
            addModulesAll(srcModulesDir, fpBuilder, targetResources, fpPackagesDir);
        } else{
            getLog().warn("No modules found at " + srcModulesDir);
        }

        final Path contentDir = targetResources.resolve(Constants.CONTENT);
        if (Files.exists(contentDir)) {
            try {
                packageContent(fpBuilder, contentDir, fpPackagesDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process content", e);
            }
        }


        if(wfFpConfig.hasSchemaGroups()) {
            addDocsSchemas(fpPackagesDir, fpBuilder);
        }

        addConfigPackages(targetResources.resolve(WfConstants.CONFIG).resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);

        final PackageSpec.Builder docsBuilder = getExtendedPackage(WfConstants.DOCS, false);
        if(docsBuilder != null) {
            fpBuilder.getSpecBuilder().addDefaultPackage(addPackage(fpPackagesDir, fpBuilder, docsBuilder).getName());
            if(fpBuilder.hasPackage("docs.licenses.xsl")) {
                getExtendedPackage(WfConstants.DOCS_LICENSES, false).addPackageDep("docs.licenses.xsl");
            }
            if(fpBuilder.hasPackage("docs.examples.configs")) {
                getExtendedPackage("docs.examples", true).addPackageDep("docs.examples.configs", true);
            }
        }

        if (!fpDependencies.isEmpty() && !extendedPackages.isEmpty()) {
            for (Map.Entry<String, FeaturePackLayout> fpDep : fpDependencies.entrySet()) {
                final FeaturePackLayout fpDepLayout = fpDep.getValue();
                for(Map.Entry<String, PackageSpec.Builder> entry : extendedPackages.entrySet()) {
                    if(fpDepLayout.hasPackage(entry.getKey())) {
                        entry.getValue().addPackageDep(fpDep.getKey(), entry.getKey());
                    }
                }
            }
        }

        for(Map.Entry<String, PackageSpec.Builder> entry : extendedPackages.entrySet()) {
            addPackage(fpPackagesDir, fpBuilder, entry.getValue());
        }

        if(wfFpConfig.hasConfigs()) {
            for(ConfigModel config : wfFpConfig.getConfigs()) {
                try {
                    fpBuilder.getSpecBuilder().addConfig(config);
                } catch (ProvisioningDescriptionException e) {
                    throw new MojoExecutionException("Failed to add config to the feature-pack", e);
                }
            }
        }

        final FeaturePackLayout fpLayout;
        try {
            fpLayout = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpLayout.getSpec(), fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(fpDir.resolve(Constants.FEATURE_PACK_XML)), e);
        }

        copyDirIfExists(targetResources.resolve(Constants.FEATURES), fpDir.resolve(Constants.FEATURES));
        copyDirIfExists(targetResources.resolve(Constants.FEATURE_GROUPS), fpDir.resolve(Constants.FEATURE_GROUPS));

        final Artifact mvnPluginsArtifact = project.getPluginArtifactMap().get("org.wildfly.galleon-plugins:wildfly-galleon-maven-plugins");
        addWildFlyPlugin(fpDir, mvnPluginsArtifact);

        // collect feature-pack resources
        final Path resourcesWildFly = fpDir.resolve(Constants.RESOURCES).resolve(WfConstants.WILDFLY);
        mkdirs(resourcesWildFly);
        addConfigGenerator(resourcesWildFly, mvnPluginsArtifact);

        // properties
        try(OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_TASKS_PROPS))) {
                getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // artifact versions
        try {
            this.artifactVersions.store(resourcesWildFly.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store artifact versions", e);
        }

        // scripts
        final Path scriptsDir = targetResources.resolve(WfConstants.SCRIPTS);
        if(Files.exists(scriptsDir)) {
            if(!Files.isDirectory(scriptsDir)) {
                throw new MojoExecutionException(WfConstants.SCRIPTS + " is not a directory");
            }
            try {
                IoUtils.copy(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS));
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS)), e);
            }
        }

        try {
            repoSystem.install(repoSession, mavenPluginUtil.getInstallLayoutRequest(workDir, project));
        } catch (InstallationException | IOException e) {
            throw new MojoExecutionException(FpMavenErrors.featurePackInstallation(), e);
        }
    }

    private static PackageSpec addPackage(final Path fpPackagesDir, final FeaturePackLayout.Builder fpBuilder,
            final PackageSpec.Builder pkgBuilder) throws MojoExecutionException {
        final PackageSpec pkg = pkgBuilder.build();
        fpBuilder.addPackage(pkg);
        writeXml(pkg, fpPackagesDir.resolve(pkg.getName()));
        return pkg;
    }

    private PackageSpec.Builder getExtendedPackage(String name, boolean create) {
        PackageSpec.Builder pkgBuilder = extendedPackages.get(name);
        if(pkgBuilder == null) {
            if(!create) {
                return null;
            }
            pkgBuilder = PackageSpec.builder(name);
            extendedPackages = CollectionUtils.put(extendedPackages, name, pkgBuilder);
        }
        return pkgBuilder;
    }

    private void addModulesAll(final Path srcModulesDir, final FeaturePackLayout.Builder fpBuilder, final Path targetResources, final Path fpPackagesDir) throws MojoExecutionException {
        getLog().debug("WfFeaturePackBuildMojo adding modules.all");
        final PackageSpec.Builder modulesAll = getExtendedPackage(WfConstants.MODULES_ALL, true);
        try {
            final Map<String, Path> moduleXmlByPkgName = findModules(srcModulesDir);
            if (moduleXmlByPkgName.isEmpty()) {
                throw new MojoExecutionException("Modules not found in " + srcModulesDir);
            }
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, fpPackagesDir, modulesAll);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
    }

    private void copyDirIfExists(final Path srcDir, final Path targetDir) throws MojoExecutionException {
        if(Files.exists(srcDir)) {
            try {
                IoUtils.copy(srcDir, targetDir);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(srcDir, targetDir), e);
            }
        }
    }

    private void addWildFlyPlugin(final Path fpDir, Artifact mvnPluginsArtifact)
            throws MojoExecutionException {
        final Path pluginsDir = fpDir.resolve(Constants.PLUGINS);
        mkdirs(pluginsDir);
        Path wfPlugInPath;
        try {
            wfPlugInPath = resolveArtifact(ArtifactCoords.newInstance(mvnPluginsArtifact.getGroupId(), "wildfly-galleon-plugins", mvnPluginsArtifact.getVersion(), "jar"));
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to build feature-pack", e);
        }
        try {
            IoUtils.copy(wfPlugInPath, pluginsDir.resolve(wfPlugInPath.getFileName()));
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(wfPlugInPath, pluginsDir.resolve(wfPlugInPath.getFileName())));
        }
    }

    private void addConfigGenerator(final Path resourcesDir, Artifact mvnPluginsArtifact) throws MojoExecutionException {
        mkdirs(resourcesDir);
        Path wfPlugInPath;
        try {
            wfPlugInPath = resolveArtifact(ArtifactCoords.newInstance(mvnPluginsArtifact.getGroupId(), "wildfly-config-gen", mvnPluginsArtifact.getVersion(), "jar"));
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to build feature-pack", e);
        }
        try {
            IoUtils.copy(wfPlugInPath, resourcesDir.resolve("wildfly-config-gen.jar"));
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(wfPlugInPath, resourcesDir.resolve(wfPlugInPath.getFileName())));
        }
    }

    private static void mkdirs(final Path resourcesWildFly) throws MojoExecutionException {
        try {
            Files.createDirectories(resourcesWildFly);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(resourcesWildFly), e);
        }
    }

    private void addDocsSchemas(final Path fpPackagesDir, final FeaturePackLayout.Builder fpBuilder)
            throws MojoExecutionException {
        getExtendedPackage(WfConstants.DOCS_SCHEMA, true);
        getExtendedPackage(WfConstants.DOCS, true).addPackageDep(WfConstants.DOCS_SCHEMA, true);
        final Path schemasPackageDir = fpPackagesDir.resolve(WfConstants.DOCS_SCHEMA);
        final Path schemaGroupsTxt = schemasPackageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.SCHEMA_GROUPS_TXT);
        BufferedWriter writer = null;
        try {
            mkdirs(schemasPackageDir);
            mkdirs(schemaGroupsTxt.getParent());
            writer = Files.newBufferedWriter(schemaGroupsTxt);
            for (String group : wfFpConfig.getSchemaGroups()) {
                writer.write(group);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(schemaGroupsTxt.getParent()), e);
        } finally {
            if(writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void addConfigPackages(final Path configDir, final Path packagesDir, final FeaturePackLayout.Builder fpBuilder) throws MojoExecutionException {
        if(!Files.exists(configDir)) {
            return;
        }
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for(Path configPackage : stream) {
                final Path packageDir = packagesDir.resolve(configPackage.getFileName());
                if (!Files.exists(packageDir)) {
                    mkdirs(packageDir);
                }
                IoUtils.copy(configPackage, packageDir);

                final Path packageXml = configPackage.resolve(Constants.PACKAGE_XML);
                if (Files.exists(packageXml)) {
                    final PackageSpec pkgSpec;
                    try (BufferedReader reader = Files.newBufferedReader(packageXml)) {
                        try {
                            pkgSpec = PackageXmlParser.getInstance().parse(reader);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException("Failed to parse " + packageXml, e);
                        }
                    }
                    IoUtils.copy(packageXml, packageDir.resolve(Constants.PACKAGE_XML));
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process config packages", e);
        }
    }

    private void processFeaturePackDependencies(final FeaturePackSpec.Builder fpBuilder) throws Exception {
        if(wfFpConfig.getDependencies().isEmpty()) {
            return;
        }

        fpDependencies = new LinkedHashMap<>(wfFpConfig.getDependencies().size());
        for (FeaturePackDependencySpec depSpec : wfFpConfig.getDependencies()) {
            final FeaturePackConfig depConfig = depSpec.getTarget();
            final String depStr = depConfig.getGav().toString();
            String gavStr = artifactVersions.getVersion(depStr);
            if (gavStr == null) {
                throw new MojoExecutionException("Failed resolve artifact version for " + depStr);
            }
            final ArtifactCoords.Gav depGav = ArtifactCoords.newGav(gavStr);
            final FeaturePackConfig.Builder depBuilder = FeaturePackConfig.builder(depGav);
            depBuilder.setInheritPackages(depConfig.isInheritPackages());
            if (depConfig.hasExcludedPackages()) {
                try {
                    depBuilder.excludeAllPackages(depConfig.getExcludedPackages()).build();
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to process dependencies", e);
                }
            }
            if (depConfig.hasIncludedPackages()) {
                try {
                    depBuilder.includeAllPackages(depConfig.getIncludedPackages()).build();
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to process dependencies", e);
                }
            }
            depBuilder.setInheritConfigs(depConfig.isInheritConfigs());
            if (depConfig.hasDefinedConfigs()) {
                for (ConfigModel config : depConfig.getDefinedConfigs()) {
                    depBuilder.addConfig(config);
                }
            }
            if (depConfig.hasExcludedConfigs()) {
                for (ConfigId configId : depConfig.getExcludedConfigs()) {
                    depBuilder.excludeDefaultConfig(configId);
                }
            }
            if (depConfig.hasFullModelsExcluded()) {
                for (Map.Entry<String, Boolean> entry : depConfig.getFullModelsExcluded().entrySet()) {
                    depBuilder.excludeConfigModel(entry.getKey(), entry.getValue());
                }
            }
            if (depConfig.hasFullModelsIncluded()) {
                for (String model : depConfig.getFullModelsIncluded()) {
                    depBuilder.includeConfigModel(model);
                }
            }
            if (depConfig.hasIncludedConfigs()) {
                for (ConfigId includedConfig : depConfig.getIncludedConfigs()) {
                    depBuilder.includeDefaultConfig(includedConfig);
                }
            }
            if (depConfig.hasDefinedConfigs()) {
                for (ConfigModel config : depConfig.getDefinedConfigs()) {
                    depBuilder.addConfig(config);
                }
            }
            fpBuilder.addFeaturePackDep(depSpec.getName(), depBuilder.build());
            final Path depZip = resolveArtifact(depGav.toArtifactCoords());
            fpDependencies.put(depSpec.getName(), FeaturePackLayoutDescriber.describeFeaturePackZip(depZip));
        }
    }

    private void packageContent(FeaturePackLayout.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                if(pkgName.equals(WfConstants.DOCS)) {
                    final PackageSpec.Builder docsBuilder = getExtendedPackage(WfConstants.DOCS, true);
                    try(DirectoryStream<Path> docsStream = Files.newDirectoryStream(p)) {
                        for(Path docPath : docsStream) {
                            final String docName = docPath.getFileName().toString();
                            final String docPkgName = WfConstants.DOCS + '.' + docName;
                            final Path pkgDir = packagesDir.resolve(docPkgName);
                            getExtendedPackage(docPkgName, true);
                            IoUtils.copy(docPath, pkgDir.resolve(Constants.CONTENT).resolve(WfConstants.DOCS).resolve(docName));
                            docsBuilder.addPackageDep(docPkgName, true);
                        }
                    }
                } else if(pkgName.equals("bin")) {
                    final Path binPkgDir = packagesDir.resolve(pkgName).resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binStandalonePkgDir = packagesDir.resolve("bin.standalone").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binDomainPkgDir = packagesDir.resolve("bin.domain").resolve(Constants.CONTENT).resolve(pkgName);
                    try (DirectoryStream<Path> binStream = Files.newDirectoryStream(p)) {
                        for (Path binPath : binStream) {
                            final String fileName = binPath.getFileName().toString();
                            if(fileName.startsWith(WfConstants.STANDALONE)) {
                                IoUtils.copy(binPath, binStandalonePkgDir.resolve(fileName));
                            } else if(fileName.startsWith(WfConstants.DOMAIN)) {
                                IoUtils.copy(binPath, binDomainPkgDir.resolve(fileName));
                            } else {
                                IoUtils.copy(binPath, binPkgDir.resolve(fileName));
                            }
                        }
                    }

                    ensureLineEndings(binPkgDir);
                    getExtendedPackage(pkgName, true);

                    if(Files.exists(binStandalonePkgDir)) {
                        ensureLineEndings(binStandalonePkgDir);
                        getExtendedPackage("bin.standalone", true).addPackageDep(pkgName);
                    }
                    if(Files.exists(binDomainPkgDir)) {
                        ensureLineEndings(binDomainPkgDir);
                        getExtendedPackage("bin.domain", true).addPackageDep(pkgName);
                    }
                } else {
                    final Path pkgDir = packagesDir.resolve(pkgName);
                    IoUtils.copy(p, pkgDir.resolve(Constants.CONTENT).resolve(pkgName));
                    final PackageSpec pkgSpec = PackageSpec.builder(pkgName).build();
                    writeXml(pkgSpec, pkgDir);
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        }
    }

    private Map<String, Path> findModules(Path modulesDir) throws IOException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if(Files.exists(moduleXml)) {
                    final String packageName = modulesDir.relativize(moduleXml.getParent()).toString().replace(File.separatorChar, '.');
                    moduleXmlByPkgName.put(packageName, moduleXml);
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
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        return moduleXmlByPkgName;
    }

    private void packageModules(FeaturePackLayout.Builder fpBuilder,
            Path resourcesDir, Map<String, Path> moduleXmlByPkgName, Path packagesDir, PackageSpec.Builder modulesAll)
            throws IOException, MojoExecutionException {

        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            final String packageName = module.getKey();
            final Path moduleXml = module.getValue();

            final Path packageDir = packagesDir.resolve(packageName);
            final Path targetXml = packageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.MODULE).resolve(resourcesDir.relativize(moduleXml));
            mkdirs(targetXml.getParent());
            IoUtils.copy(moduleXml.getParent(), targetXml.getParent());

            final PackageSpec.Builder pkgSpecBuilder = PackageSpec.builder(packageName);
            final ModuleParseResult parsedModule;
            try {
                parsedModule = ModuleXmlParser.parse(targetXml, WfConstants.UTF8);
                if (!parsedModule.dependencies.isEmpty()) {
                    for (ModuleDependency moduleDep : parsedModule.dependencies) {
                        final StringBuilder buf = new StringBuilder();
                        buf.append(moduleDep.getModuleId().getName()).append('.').append(moduleDep.getModuleId().getSlot());
                        final String depName = buf.toString();
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            pkgSpecBuilder.addPackageDep(depName, moduleDep.isOptional());
                            continue;
                        }
                        Map.Entry<String, FeaturePackLayout> depSrc = null;
                        if (!fpDependencies.isEmpty()) {
                            Set<String> alternativeSrc = Collections.emptySet();
                            for (Map.Entry<String, FeaturePackLayout> depEntry : fpDependencies.entrySet()) {
                                if (depEntry.getValue().hasPackage(depName)) {
                                    if (depSrc != null) {
                                        alternativeSrc = CollectionUtils.add(alternativeSrc, depSrc.getKey());
                                    }
                                    depSrc = depEntry;
                                }
                            }
                            if (!alternativeSrc.isEmpty()) {
                                final StringBuilder warn = new StringBuilder();
                                warn.append("Package ").append(depName).append(" from ").append(depSrc.getKey())
                                        .append(" picked as dependency of ").append(packageName).append(" although ")
                                        .append(depName).append(" also exists in ");
                                StringUtils.append(warn, alternativeSrc);
                                getLog().warn(warn);
                            }
                        }
                        if (depSrc != null) {
                            pkgSpecBuilder.addPackageDep(depSrc.getKey(), depName, moduleDep.isOptional());
                        } else if (moduleDep.isOptional() || isProvided(depName)) {
                            // getLog().warn("UNSATISFIED EXTERNAL OPTIONAL DEPENDENCY " + packageName + " -> " + depName);
                        } else {
                            throw new MojoExecutionException(
                                    "Package " + packageName + " has unsatisifed external dependency on package " + depName);
                        }
                    }
                }
            } catch (ParsingException e) {
                throw new IOException(Errors.parseXml(targetXml), e);
            }

            final PackageSpec pkgSpec = pkgSpecBuilder.build();
            try {
                PackageXmlWriter.getInstance().write(pkgSpec, packageDir.resolve(Constants.PACKAGE_XML));
            } catch (XMLStreamException e) {
                throw new IOException(Errors.writeFile(packageDir.resolve(Constants.PACKAGE_XML)), e);
            }
            modulesAll.addPackageDep(packageName, true);
            fpBuilder.addPackage(pkgSpec);
        }
    }

    private Properties getFPConfigProperties() {
        final Properties properties = new Properties();
        properties.put("project.version", project.getVersion());
        properties.put("product.release.name", releaseName);
        return properties;
    }

    private Path getFPConfigFile() throws ProvisioningException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if(!Files.exists(path)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(path));
        }
        return path;
    }

    private static void writeXml(PackageSpec pkgSpec, Path dir) throws MojoExecutionException {
        try {
            mkdirs(dir);
            PackageXmlWriter.getInstance().write(pkgSpec, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeFile(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, getArtifactRequest(coords));
        } catch (ArtifactResolutionException e) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords), e);
        }
        if(!result.isResolved()) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords));
        }
        if(result.isMissing()) {
            throw new ProvisioningException(FpMavenErrors.artifactMissing(coords));
        }
        return Paths.get(result.getArtifact().getFile().toURI());
    }

    private ArtifactRequest getArtifactRequest(ArtifactCoords coords) {
        final ArtifactRequest req = new ArtifactRequest();
        req.setArtifact(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getExtension(), coords.getVersion()));
        req.setRepositories(remoteRepos);
        return req;
    }

    private static void ensureLineEndings(Path file) throws MojoExecutionException {
        try {
            Files.walkFileTree(file, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if(linuxLineEndingsPathFilter.accept(file)) {
                        ensureLineEndings(file, linuxLineEndingPattern, "\n");
                    } else if(windowsLineEndingsPathFilter.accept(file)) {
                        ensureLineEndings(file, windowsLineEndingPattern, "\r\n");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to adjust line endings for " + file, e);
        }
    }

    private static void ensureLineEndings(Path file, Pattern pattern, String lineEnding) throws IOException {
        final String content = IoUtils.readFile(file);
        final Matcher matcher = pattern.matcher(content);
        final String fixedContent = matcher.replaceAll(lineEnding);
        if(content.equals(fixedContent)) {
            return;
        }
        try(ByteArrayInputStream in = new ByteArrayInputStream(fixedContent.getBytes(WfConstants.UTF8))) {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
