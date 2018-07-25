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
import java.io.Reader;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.maven.plugin.FpMavenErrors;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.PathFilter;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.util.ZipUtils;
import org.jboss.galleon.xml.FeaturePackXmlWriter;
import org.jboss.galleon.xml.PackageXmlParser;
import org.jboss.galleon.xml.PackageXmlWriter;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.maven.ModuleParseResult.ModuleDependency;

/**
 * This Maven mojo creates a WildFly style feature-pack archive from the provided resources according to the
 * feature-pack build configuration file and attaches it to the current Maven project as an artifact.
 *
 * The content of the future feature-pack archive is first created in the directory called `layout` under the module's
 * build directory which is then ZIPped to create the feature-pack artifact.
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
                module.equals("org.jboss.modules");
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
     * The feature-pack build configuration file.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * The feature-pack build configuration file directory
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * A path relative to {@link #configDir} that represents the directory under which of resources such as
     * {@code packages}, {@code feature_groups}, etc.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;


    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    /**
     * The name of the release the feature-pack represents which will be stored in the feature-pack's
     * `resources/wildfly/wildfly-tasks.properties` as `product.release.name` property.
     */
    @Parameter(alias="release-name", defaultValue = "${product.release.name}")
    private String releaseName;

    /**
     * Path to a properties file content of which will be added to feature-pack's
     * `resources/wildfly/wildfly-tasks.properties` file that is used as the source of properties during
     * file copying tasks with property replacement.
     */
    @Parameter(alias="task-properties-file", required=false)
    private File taskPropsFile;

    /**
     * Various properties that will be added to feature-pack's `resources/wildfly/wildfly-tasks.properties`.<br/>
     * NOTE: values of this parameter will overwrite the corresponding values from task-properties-file parameter, in case it's also set.<br/>
     */
    @Parameter(alias="task-properties", required=false)
    private Map<String, String> taskProps = Collections.emptyMap();

    /**
     * The artifactId for the generated feature-pack.
     */
    @Parameter(alias="feature-pack-artifact-id", defaultValue = "${project.artifactId}", required=false)
    private String fpArtifactId;

    @Component
    private MavenProjectHelper projectHelper;

    private MavenProjectArtifactVersions artifactVersions;

    private WildFlyFeaturePackBuild wfFpConfig;
    private Map<String, FeaturePackDescription> fpDependencies = Collections.emptyMap();
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
        final Path specsDir = Paths.get(configDir.getAbsolutePath()).resolve(resourcesDir);
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


        // feature-pack build config
        try {
            wfFpConfig = Util.loadFeaturePackBuildConfig(getFPConfigFile());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to load feature-pack config file", e);
        }

        FeaturePackLocation fpl = wfFpConfig.getProducer();
        String channel = fpl.getChannelName();
        if(channel == null || channel.isEmpty()) {
            final String v = project.getVersion();
            final int i = v.indexOf('.');
            channel = i < 0 ? v : v.substring(0, i);
        }
        fpl = new FeaturePackLocation(fpl.getUniverse(), fpl.getProducerName(), channel, null, project.getVersion());

        // feature-pack builder
        final FeaturePackDescription.Builder fpBuilder = FeaturePackDescription.builder(FeaturePackSpec.builder(fpl.getFPID()));

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

        final Path srcModulesDir = targetResources.resolve(WfConstants.MODULES);
        if (Files.exists(srcModulesDir)) {
            addModulePackages(srcModulesDir, fpBuilder, targetResources, fpPackagesDir);
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

        addConfigPackages(targetResources.resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);

        final PackageSpec.Builder docsBuilder = getExtendedPackage(WfConstants.DOCS, false);
        if(docsBuilder != null) {
            fpBuilder.getSpecBuilder().addDefaultPackage(addPackage(fpPackagesDir, fpBuilder, docsBuilder).getName());
//            if(fpBuilder.hasPackage("docs.examples.configs")) {
//                getExtendedPackage("docs.examples", true).addPackageDep("docs.examples.configs", true);
//            }
        }

        if (!fpDependencies.isEmpty() && !extendedPackages.isEmpty()) {
            for (Map.Entry<String, FeaturePackDescription> fpDep : fpDependencies.entrySet()) {
                final FeaturePackDescription fpDepLayout = fpDep.getValue();
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

        final FeaturePackDescription fpLayout;
        try {
            fpLayout = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpLayout.getSpec(), fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(fpDir.resolve(Constants.FEATURE_PACK_XML)), e);
        }

        copyDirIfExists(targetResources.resolve(Constants.FEATURES), fpDir.resolve(Constants.FEATURES));
        copyDirIfExists(targetResources.resolve(Constants.FEATURE_GROUPS), fpDir.resolve(Constants.FEATURE_GROUPS));

        final Artifact mvnPluginsArtifact = project.getPluginArtifactMap().get("org.wildfly.galleon-plugins:wildfly-galleon-maven-plugin");
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

        // build feature-packs from the layout and attach as project artifacts
        try (DirectoryStream<Path> wdStream = Files.newDirectoryStream(workDir, entry -> Files.isDirectory(entry))) {
            for (Path groupDir : wdStream) {
                try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(groupDir)) {
                    for (Path artifactDir : groupStream) {
                        final String artifactId = artifactDir.getFileName().toString();
                        try (DirectoryStream<Path> artifactStream = Files.newDirectoryStream(artifactDir)) {
                            for (Path versionDir : artifactStream) {
                                final Path target = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-' + versionDir.getFileName() + ".zip");
                                if(Files.exists(target)) {
                                    IoUtils.recursiveDelete(target);
                                }
                                ZipUtils.zip(versionDir, target);
                                debug("Attaching feature-pack %s as a project artifact", target);
                                projectHelper.attachArtifact(project, "zip", target.toFile());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create a feature-pack archives from the layout", e);
        }
    }

    private static PackageSpec addPackage(final Path fpPackagesDir, final FeaturePackDescription.Builder fpBuilder,
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

    private void addModulePackages(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder, final Path targetResources, final Path fpPackagesDir) throws MojoExecutionException {
        debug("WfFeaturePackBuildMojo adding module packages");
        final Path layersDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS);
        if (Files.exists(layersDir)) {
            final PackageSpec.Builder modulesAll = getExtendedPackage(WfConstants.MODULES_ALL, true);
            try (Stream<Path> layers = Files.list(layersDir)) {
                final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
                final Iterator<Path> i = layers.iterator();
                while (i.hasNext()) {
                    final Path layerDir = i.next();
                    findModules(layerDir, moduleXmlByPkgName);
                    if (moduleXmlByPkgName.isEmpty()) {
                        throw new MojoExecutionException("Modules not found in " + layerDir);
                    }
                }
                packageModules(fpBuilder, targetResources, moduleXmlByPkgName, fpPackagesDir, modulesAll);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process modules content", e);
            }
        }
        final Path layersConf = srcModulesDir.resolve(WfConstants.LAYERS_CONF);
        if(!Files.exists(layersConf)) {
            return;
        }
        final Path targetPath = fpPackagesDir.resolve(WfConstants.LAYERS_CONF).resolve(Constants.CONTENT).resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
        try {
            Files.createDirectories(targetPath.getParent());
            IoUtils.copy(layersConf, targetPath);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(layersConf, targetPath), e);
        }
        final PackageSpec.Builder pkgBuilder = PackageSpec.builder(WfConstants.LAYERS_CONF);
        addPackage(fpPackagesDir, fpBuilder, pkgBuilder);
        fpBuilder.getSpecBuilder().addDefaultPackage(WfConstants.LAYERS_CONF);
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
            wfPlugInPath = resolveArtifact(ArtifactCoords.newInstance(mvnPluginsArtifact.getGroupId(), WfConstants.WF_GALLEON_PLUGINS, mvnPluginsArtifact.getVersion(), "jar"));
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to build feature-pack", e);
        }
        try {
            IoUtils.copy(wfPlugInPath, pluginsDir.resolve(WfConstants.WF_GALLEON_PLUGINS + WfConstants.DOT_JAR));
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(wfPlugInPath, pluginsDir.resolve(WfConstants.WF_GALLEON_PLUGINS + WfConstants.DOT_JAR)));
        }
    }

    private void addConfigGenerator(final Path resourcesDir, Artifact mvnPluginsArtifact) throws MojoExecutionException {
        mkdirs(resourcesDir);
        Path wfPlugInPath;
        try {
            wfPlugInPath = resolveArtifact(ArtifactCoords.newInstance(mvnPluginsArtifact.getGroupId(), WfConstants.WF_CONFIG_GEN, mvnPluginsArtifact.getVersion(), "jar"));
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to build feature-pack", e);
        }
        try {
            IoUtils.copy(wfPlugInPath, resourcesDir.resolve(WfConstants.WF_CONFIG_GEN + WfConstants.DOT_JAR));
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

    private void addDocsSchemas(final Path fpPackagesDir, final FeaturePackDescription.Builder fpBuilder)
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

    private void addConfigPackages(final Path configDir, final Path packagesDir, final FeaturePackDescription.Builder fpBuilder) throws MojoExecutionException {
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
        for (Map.Entry<ArtifactCoords.Gav, FeaturePackDependencySpec> depEntry : wfFpConfig.getDependencies().entrySet()) {
            ArtifactCoords.Gav depGav = depEntry.getKey();
            if (depGav.getVersion() == null) {
                String gavStr = artifactVersions.getVersion(depGav.toString());
                if (gavStr == null) {
                    throw new MojoExecutionException("Failed resolve artifact version for " + depGav);
                }
                depGav = ArtifactCoords.newGav(gavStr);
            }

            final FeaturePackDependencySpec depSpec = depEntry.getValue();
            final FeaturePackConfig depConfig = depSpec.getTarget();

            FeaturePackLocation depFpl = depConfig.getLocation();
            String channel = depFpl.getChannelName();
            if(channel == null || channel.isEmpty()) {
                final String v = depGav.getVersion();
                int i = v.indexOf('.');
                channel = i < 0 ? v : v.substring(0, i);
            }
            depFpl = new FeaturePackLocation(depFpl.getUniverse(), depFpl.getProducerName(), channel, null, depGav.getVersion());

            fpBuilder.addFeaturePackDep(depSpec.getName(), FeaturePackConfig.builder(depFpl).init(depConfig).build());
            final Path depZip = resolveArtifact(depGav.toArtifactCoords());
            fpDependencies.put(depSpec.getName(), FeaturePackDescriber.describeFeaturePackZip(depZip));
        }
    }

    private void packageContent(FeaturePackDescription.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                final Path pkgDir = packagesDir.resolve(pkgName);
                final Path pkgContentDir = pkgDir.resolve(Constants.CONTENT).resolve(pkgName);
                final PackageSpec.Builder pkgBuilder = getExtendedPackage(pkgName, true);
                if(pkgName.equals(WfConstants.DOCS)) {
                    try(DirectoryStream<Path> docsStream = Files.newDirectoryStream(p)) {
                        for(Path docPath : docsStream) {
                            final String docName = docPath.getFileName().toString();
                            final String docPkgName = WfConstants.DOCS + '.' + docName;
                            final Path docDir = packagesDir.resolve(docPkgName);
                            getExtendedPackage(docPkgName, true);
                            final Path docContentDir = docDir.resolve(Constants.CONTENT).resolve(WfConstants.DOCS).resolve(docName);
                            IoUtils.copy(docPath, docContentDir);
                            pkgBuilder.addPackageDep(docPkgName, true);
                            ensureLineEndings(docContentDir);
                        }
                    }
                } else if(pkgName.equals("bin")) {
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
                                IoUtils.copy(binPath, pkgContentDir.resolve(fileName));
                            }
                        }
                    }

                    if(Files.exists(binStandalonePkgDir)) {
                        ensureLineEndings(binStandalonePkgDir);
                        getExtendedPackage("bin.standalone", true).addPackageDep(pkgName);
                    }
                    if(Files.exists(binDomainPkgDir)) {
                        ensureLineEndings(binDomainPkgDir);
                        getExtendedPackage("bin.domain", true).addPackageDep(pkgName);
                    }
                } else {
                    IoUtils.copy(p, pkgContentDir);
                }
                if(Files.exists(pkgContentDir)) {
                    ensureLineEndings(pkgContentDir);
                }
            }
        }
    }

    private void findModules(Path modulesDir, Map<String, Path> moduleXmlByPkgName) throws IOException {
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if(!Files.exists(moduleXml)) {
                    return FileVisitResult.CONTINUE;
                }

                String packageName;
                if (moduleXml.getParent().getFileName().toString().equals("main")) {
                    packageName = modulesDir.relativize(moduleXml.getParent().getParent()).toString();
                } else {
                    packageName = modulesDir.relativize(moduleXml.getParent()).toString();
                }
                packageName = packageName.replace(File.separatorChar, '.');
                moduleXmlByPkgName.put(packageName, moduleXml);
                return FileVisitResult.SKIP_SUBTREE;
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
    }

    private void packageModules(FeaturePackDescription.Builder fpBuilder,
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
                        final ModuleIdentifier moduleId = moduleDep.getModuleId();
                        String depName = moduleId.getName();
                        if(!moduleId.getSlot().equals("main")) {
                            depName += '.' + moduleId.getSlot();
                        }
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            pkgSpecBuilder.addPackageDep(depName, moduleDep.isOptional());
                            continue;
                        }
                        Map.Entry<String, FeaturePackDescription> depSrc = null;
                        if (!fpDependencies.isEmpty()) {
                            Set<String> alternativeSrc = Collections.emptySet();
                            for (Map.Entry<String, FeaturePackDescription> depEntry : fpDependencies.entrySet()) {
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

    private Properties getFPConfigProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        properties.put("project.version", project.getVersion());
        properties.put("version", project.getVersion()); // needed for licenses.xsl
        if (releaseName != null) {
            properties.put("product.release.name", releaseName);
        }
        if(taskPropsFile != null) {
            final Path p = taskPropsFile.toPath();
            if(!Files.exists(p)) {
                throw new MojoExecutionException(Errors.pathDoesNotExist(p));
            }
            try (Reader reader = Files.newBufferedReader(p)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.readFile(p), e);
            }
        }
        if(!taskProps.isEmpty()) {
            properties.putAll(taskProps);
        }
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

    private void debug(String msg, Object... args) {
        if(getLog().isDebugEnabled()) {
            getLog().debug(String.format(msg, args));
        }
    }
}
