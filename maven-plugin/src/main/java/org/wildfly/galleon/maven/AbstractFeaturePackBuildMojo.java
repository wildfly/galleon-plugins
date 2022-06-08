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

import static java.lang.String.format;
import static org.wildfly.galleon.maven.FeatureSpecGeneratorInvoker.MODULE_PATH_SEGMENT;
import static org.wildfly.galleon.maven.Util.mkdirs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.TreeSet;

import javax.xml.stream.XMLStreamException;

import nu.xom.ParsingException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.maven.plugin.FpMavenErrors;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.spec.FeaturePackPlugin;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.PackageDependencySpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.util.ZipUtils;
import org.jboss.galleon.xml.FeaturePackXmlWriter;
import org.jboss.galleon.xml.PackageXmlParser;
import org.jboss.galleon.xml.PackageXmlWriter;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelRequirement;
import org.wildfly.galleon.maven.build.tasks.ResourcesTask;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WfInstallPlugin;

/**
 * This Maven mojo creates a WildFly style feature-pack archive from the
 * provided resources according to the feature-pack build configuration file and
 * attaches it to the current Maven project as an artifact.
 *
 * The content of the future feature-pack archive is first created in the
 * directory called `layout` under the module's build directory which is then
 * ZIPped to create the feature-pack artifact.
 *
 * @author Alexey Loubyansky
 */
public abstract class AbstractFeaturePackBuildMojo extends AbstractMojo {

    static final String ARTIFACT_LIST_CLASSIFIER = "artifact-list";
    static final String ARTIFACT_LIST_EXTENSION = "txt";

    static final String CHANNEL_CLASSIFIER = "channel";
    static final String CHANNEL_EXTENSION = "yaml";

    static boolean isProvided(String module) {
        return module.startsWith("java.")
                || module.startsWith("jdk.")
                || module.equals("org.jboss.modules");
    }

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> repositories;

    /**
     * The name of the release the feature-pack represents which will be stored
     * in the feature-pack's `resources/wildfly/wildfly-tasks.properties` as
     * `product.release.name` property.
     */
    @Parameter(alias = "release-name", defaultValue = "${product.release.name}")
    private String releaseName;

    /**
     * Path to a properties file content of which will be added to
     * feature-pack's `resources/wildfly/wildfly-tasks.properties` file that is
     * used as the source of properties during file copying tasks with property
     * replacement.
     */
    @Parameter(alias = "task-properties-file", required = false)
    private File taskPropsFile;

    /**
     * Various properties that will be added to feature-pack's
     * `resources/wildfly/wildfly-tasks.properties`.<br/>
     * NOTE: values of this parameter will overwrite the corresponding values
     * from task-properties-file parameter, in case it's also set.<br/>
     */
    @Parameter(alias = "task-properties", required = false)
    protected Map<String, String> taskProps = Collections.emptyMap();

    /**
     * Whether to transform artifacts from javax.* to jakarta.* before generating feature specs.
     */
    @Parameter(alias = "jakarta-transform", required = false)
    protected boolean jakartaTransform;

    /**
     * Directory where external user provided transformation configs are located (turns of default transformation rules).
     */
    @Parameter(alias = "jakarta-transform-configs-dir", required = false)
    protected File jakartaTransformConfigsDir;

    /**
     * If jakarta-transform is true, whether to produce verbose log output of the transformation work.
     */
    @Parameter(alias = "jakarta-transform-verbose", required = false)
    protected boolean jakartaTransformVerbose;

    /**
     * The directory where a generated local maven repo containing Jakarta-transformed artifacts are stored.
     */
    @Parameter(alias = "jakarta-transform-maven-repo", defaultValue = "${project.build.directory}/jakarta-transform-maven-repo", required = true)
    protected File jakartaTransformRepo;

    /**
     * A list of regular expression filters to exclude a list of
     * GroupId:ArtifactId from jakarta transformation. For example, to exclude
     * wildfly-ee and smallrye-config artifacts:
     * <br/>
     * <pre>
     * {@code
     * <jakarta-transform-excluded-artifacts>
     *   <exclude>org.wildfly:wildfly-ee\z</exclude>
     *   <exclude>io.smallrye.config:smallrye-config\z</exclude>
     * </jakarta-transform-excluded-artifacts>
     * }
     * </pre>
     */
    @Parameter(alias = "jakarta-transform-excluded-artifacts", required = false)
    protected List<String> jakartaTransformExcludedArtifacts;

    /**
     * Generates a channel YAML definition when the feature-pack is produced.
     * Any dependency from the feature pack is declared as a stream in the channel.
     */
    @Parameter(alias = "generate-channel", required = false, defaultValue = "false")
    protected boolean channelGenerated;

    /**
     * Add any feature-pack dependency as a required channel in the channel YAML definition.
     * This parameter has no effect if "generate-channel" is false.
     */
    @Parameter(alias = "add-feature-packs-as-required-channels", required = false, defaultValue = "true")
    protected boolean addFeaturePacksAsRequiredChannels;

    @Component
    protected RepositorySystem repoSystem;

    @Component
    protected ArtifactResolver artifactResolver;

    @Component
    private MavenProjectHelper projectHelper;

    private MavenProjectArtifactVersions artifactVersions;

    private Map<String, FeaturePackDescription> fpDependencies = Collections.emptyMap();

    private Path workDir;
    private Path fpDir;
    private Path fpPackagesDir;
    private Path resourcesWildFly;
    private Path fpResourcesDir;
    private Path resourcesDir;

    private JakartaTransformation jakartaTransformation;
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            artifactVersions = MavenProjectArtifactVersions.getInstance(project);
            jakartaTransformation = new JakartaTransformation(getLog(), jakartaTransform, jakartaTransformVerbose,
                    jakartaTransformConfigsDir, jakartaTransformRepo, taskProps.get(WfInstallPlugin.JAKARTA_TRANSFORM_SUFFIX_KEY),
                    jakartaTransformExcludedArtifacts);
            doExecute();
        } catch (RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        }
    }

    protected JakartaTransformation getJakartaTransformation() {
        return jakartaTransformation;
    }

    protected Map<String, FeaturePackDescription> getFpDependencies() {
        return fpDependencies;
    }

    protected MavenProjectArtifactVersions getArtifactVersions() {
        return artifactVersions;
    }

    protected abstract void doExecute() throws MojoExecutionException, MojoFailureException;

    protected void setupDirs(String buildName, String fpArtifactId, String layoutDir, Path resourcesDir) {
        if (workDir == null) {
            workDir = Paths.get(buildName, layoutDir);
            IoUtils.recursiveDelete(workDir);
            fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
            fpPackagesDir = fpDir.resolve(Constants.PACKAGES);
            fpResourcesDir = fpDir.resolve(Constants.RESOURCES);
            resourcesWildFly = fpResourcesDir.resolve(WfConstants.WILDFLY);
            this.resourcesDir = resourcesDir;
        }
    }

    protected Path getWorkDir() {
        Objects.requireNonNull(workDir);
        return workDir;
    }

    protected Path getWildFlyResourcesDir() {
        return resourcesWildFly;
    }

    protected Path getFpDir() {
        Objects.requireNonNull(fpDir);
        return fpDir;
    }

    protected Path getPackagesDir() {
        Objects.requireNonNull(fpPackagesDir);
        return fpPackagesDir;
    }

    public String resolveVersion(final String coordsWoVersion) throws MojoExecutionException {
        final String resolved = artifactVersions.getVersion(coordsWoVersion);
        if (resolved == null) {
            throw new MojoExecutionException("The project is missing dependency on " + coordsWoVersion);
        }
        return resolved;
    }

    protected void transformOnlyArtifactDependencies(WildFlyFeaturePackBuild buildConfig) throws MojoExecutionException {
        if (getJakartaTransformation().isJakartaTransformEnabled()) {
            // We simply need to transform dependencies of this FP to generate an exclusion list
            for (Artifact artifact : MavenProjectArtifactVersions.getFilteredArtifacts(project, buildConfig)) {
                try {
                    getJakartaTransformation().transform(artifact);
                } catch (IOException ex) {
                    throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
                }
            }
        }
    }

    protected void buildFeaturePack(FeaturePackDescription.Builder fpBuilder, WildFlyFeaturePackBuild buildConfig) throws MojoExecutionException {
        if (buildConfig.hasConfigs()) {
            for (ConfigModel config : buildConfig.getConfigs()) {
                try {
                    fpBuilder.getSpecBuilder().addConfig(config);
                } catch (ProvisioningDescriptionException e) {
                    throw new MojoExecutionException("Failed to add config to the feature-pack", e);
                }
            }
        }

        if (buildConfig.hasPlugins()) {
            addPlugins(fpBuilder.getSpecBuilder(), buildConfig.getPlugins());
        }

        Util.mkdirs(resourcesWildFly);
        if (buildConfig.hasResourcesTasks()) {
            for (ResourcesTask task : buildConfig.getResourcesTasks()) {
                task.execute(this, fpResourcesDir);
            }
        }

        // properties
        try (OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_TASKS_PROPS))) {
            getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // artifact versions
        for (ArtifactCoords.Gav gav : buildConfig.getDependencies().keySet()) {
            getArtifactVersions().remove(gav.getGroupId(), gav.getArtifactId());
        }

        // Generate excluded from transformations
        if (jakartaTransformation.isJakartaTransformEnabled()) {
            jakartaTransformation.writeExcludedArtifactsFile(resourcesWildFly);
        }

        // Copy resources from src.
        try {
            Path srcArtifacts = resourcesDir.resolve(Constants.RESOURCES);
            if (Files.exists(srcArtifacts)) {
               IoUtils.copy(srcArtifacts, fpResourcesDir);
            }
            getArtifactVersions().store(resourcesWildFly.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS));
        } catch (ProvisioningException | IOException e) {
            throw new MojoExecutionException("Failed to store artifact versions", e);
        }
        // Build a maven artifact resolver that looks into the local maven repo, not in the project
        // built artifacts.
        DefaultRepositorySystemSession noWorkspaceSession = new DefaultRepositorySystemSession(repoSession);
        noWorkspaceSession.setWorkspaceReader(null);
        ArtifactListBuilder builder = new ArtifactListBuilder(new MavenArtifactRepositoryManager(repoSystem,
                noWorkspaceSession), repoSession.getLocalRepository().getBasedir().toPath(), getLog());
        buildArtifactList(builder);

        addConfigPackages(resourcesDir.resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);
        Util.copyIfExists(resourcesDir, fpDir, Constants.LAYERS);
        Util.copyIfExists(resourcesDir, fpDir, Constants.CONFIGS);

        Util.copyDirIfExists(resourcesDir.resolve(Constants.FEATURES), fpDir.resolve(Constants.FEATURES));
        Util.copyDirIfExists(resourcesDir.resolve(Constants.FEATURE_GROUPS), fpDir.resolve(Constants.FEATURE_GROUPS));

        final Path resourcesWildFly = getWildFlyResourcesDir();
        if (buildConfig.hasStandaloneExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_STANDALONE, buildConfig.getStandaloneExtensions());
        }
        if (buildConfig.hasDomainExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_DOMAIN, buildConfig.getDomainExtensions());
        }
        if (buildConfig.hasHostExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_HOST, buildConfig.getHostExtensions());
        }

        // scripts
        final Path scriptsDir = resourcesDir.resolve(WfConstants.SCRIPTS);
        if (Files.exists(scriptsDir)) {
            if (!Files.isDirectory(scriptsDir)) {
                throw new MojoExecutionException(WfConstants.SCRIPTS + " is not a directory");
            }
            try {
                IoUtils.copy(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS));
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS)), e);
            }
        }

        final FeaturePackDescription fpLayout;
        try {
            fpLayout = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpLayout.getSpec(), getFpDir().resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(getFpDir().resolve(Constants.FEATURE_PACK_XML)), e);
        }

        // build feature-packs from the layout and attach as project artifacts
        try (DirectoryStream<Path> wdStream = Files.newDirectoryStream(getWorkDir(), entry -> Files.isDirectory(entry))) {
            for (Path groupDir : wdStream) {
                try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(groupDir)) {
                    for (Path artifactDir : groupStream) {
                        final String artifactId = artifactDir.getFileName().toString();
                        try (DirectoryStream<Path> artifactStream = Files.newDirectoryStream(artifactDir)) {
                            for (Path versionDir : artifactStream) {
                                final Path target = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-' + versionDir.getFileName() + ".zip");
                                if (Files.exists(target)) {
                                    IoUtils.recursiveDelete(target);
                                }
                                ZipUtils.zip(versionDir, target);
                                debug("Attaching feature-pack %s as a project artifact", target);
                                projectHelper.attachArtifact(project, "zip", target.toFile());
                                final Path offLinerTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                        + versionDir.getFileName() + "-" + ARTIFACT_LIST_CLASSIFIER + "." + ARTIFACT_LIST_EXTENSION);
                                debug("Attaching feature-pack artifact list %s as a project artifact", offLinerTarget);
                                Files.write(offLinerTarget, builder.build().getBytes());
                                projectHelper.attachArtifact(project, ARTIFACT_LIST_EXTENSION, ARTIFACT_LIST_CLASSIFIER, offLinerTarget.toFile());
                                if (channelGenerated) {
                                    final Path channelTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                            + versionDir.getFileName() + "-" + CHANNEL_CLASSIFIER + "." + CHANNEL_EXTENSION);
                                    debug("Attaching channel definition %s as a project artifact", channelTarget);
                                    String channel = createYAMLChannel(buildConfig);
                                    Files.write(channelTarget, channel.getBytes());
                                    projectHelper.attachArtifact(project, CHANNEL_EXTENSION, CHANNEL_CLASSIFIER, channelTarget.toFile());
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create a feature-pack archives from the layout", e);
        }
    }

    /**
     * Create a YAML Channel that defines streams for all the feature-pack dependencies.
     * The feature-pack itself is added to the channel's streams.
     */
    private String createYAMLChannel(WildFlyFeaturePackBuild buildConfig) throws IOException {
        ArrayList<ChannelRequirement> channelRequirements = new ArrayList<>();
        if (addFeaturePacksAsRequiredChannels && !buildConfig.getDependencies().isEmpty()) {
            for (ArtifactCoords.Gav gav : new TreeSet<>(buildConfig.getDependencies().keySet())) {
                project.getDependencies().stream()
                        .filter(dep ->
                                gav.getGroupId().equals(dep.getGroupId())
                                        && gav.getArtifactId().equals(dep.getArtifactId())
                                        && "zip".equals(dep.getType()))
                        .findFirst()
                        .ifPresent( fp ->
                                channelRequirements.add(new ChannelRequirement(fp.getGroupId(), fp.getArtifactId(), fp.getVersion())));
            }
        }
        // append all feature-pack artifacts dependencies as streams (except zip and pom dependencies)
        List<org.wildfly.channel.Stream> streams = MavenProjectArtifactVersions.getFilteredArtifacts(project, buildConfig).stream()
                .filter(a -> !"zip".equals(a.getType()) && !"pom".equals(a.getType()))
                .map(artifact -> new org.wildfly.channel.Stream(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()))
                .collect(Collectors.toList());
        // add a stream for this feature pack
        streams.add(new org.wildfly.channel.Stream(project.getGroupId(), project.getArtifactId(), project.getVersion()));

        Channel channel = new Channel(format("Channel for %s feature pack.", project.getArtifact()),
                format("Generated by org.wildfly.galleon-plugins:wildfly-galleon-maven-plugin at %s", Clock.systemUTC().instant()),
               null,
               channelRequirements,
               streams);
       return ChannelMapper.toYaml(channel);
    }

    private void addConfigPackages(final Path configDir, final Path packagesDir, final FeaturePackDescription.Builder fpBuilder) throws MojoExecutionException {
        if (!Files.exists(configDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path configPackage : stream) {
                final Path packageDir = packagesDir.resolve(configPackage.getFileName());
                final Path packageFile = packageDir.resolve(Constants.PACKAGE_XML);
                final Path packageXml = configPackage.resolve(Constants.PACKAGE_XML);
                if (Files.exists(packageFile) && Files.exists(packageXml)) {
                    warn("File " + packageFile + " already exists, replacing with " + packageXml);
                }
                if (!Files.exists(packageDir)) {
                    Util.mkdirs(packageDir);
                }
                IoUtils.copy(configPackage, packageDir);

                if (Files.exists(packageXml)) {
                    final PackageSpec pkgSpec;
                    try (BufferedReader reader = Files.newBufferedReader(packageXml)) {
                        try {
                            pkgSpec = PackageXmlParser.getInstance().parse(reader);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException("Failed to parse " + packageXml, e);
                        }
                    }
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process config packages", e);
        }
    }

    private void persistExtensions(final Path resourcesWildFly, String name, List<String> extensions) throws MojoExecutionException {
        try {
            Files.write(resourcesWildFly.resolve(name), extensions);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to persist " + name, e);
        }
    }

    private Properties getFPConfigProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        properties.put("project.version", project.getVersion());
        properties.put("version", project.getVersion()); // needed for licenses.xsl
        if (releaseName != null) {
            properties.put("product.release.name", releaseName);
        }
        if (taskPropsFile != null) {
            final Path p = taskPropsFile.toPath();
            if (!Files.exists(p)) {
                throw new MojoExecutionException(Errors.pathDoesNotExist(p));
            }
            try (Reader reader = Files.newBufferedReader(p)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.readFile(p), e);
            }
        }
        if (!taskProps.isEmpty()) {
            properties.putAll(taskProps);
        }
        return properties;
    }

    private void addPlugins(FeaturePackSpec.Builder fpBuilder, Map<String, ArtifactCoords> plugins) throws MojoExecutionException {
        for (Map.Entry<String, ArtifactCoords> entry : plugins.entrySet()) {
            ArtifactCoords coords = entry.getValue();
            if (coords.getVersion() == null) {
                coords = ArtifactCoordsUtil.fromJBossModules(
                        resolveVersion(coords.getGroupId() + ':' + coords.getArtifactId()), "jar");
            }
            try {
                resolveArtifact(ArtifactCoords.newInstance(coords.getGroupId(),
                        coords.getArtifactId(), coords.getVersion(), coords.getExtension()));
            } catch (ProvisioningException e) {
                throw new MojoExecutionException("Failed to resolve feature-pack plugin " + coords, e);
            }
            final StringBuilder buf = new StringBuilder(128);
            buf.append(coords.getGroupId()).append(':')
                    .append(coords.getArtifactId()).append(':');
            final String classifier = coords.getClassifier();
            if (classifier != null && !classifier.isEmpty()) {
                buf.append(classifier).append(':');
            }
            buf.append(coords.getExtension()).append(':').append(coords.getVersion());
            fpBuilder.addPlugin(FeaturePackPlugin.getInstance(entry.getKey(), buf.toString()));
        }
    }

    protected void processFeaturePackDependencies(WildFlyFeaturePackBuild buildConfig, final FeaturePackSpec.Builder fpBuilder) throws Exception {
        if (buildConfig.getDependencies().isEmpty()) {
            return;
        }

        fpDependencies = new LinkedHashMap<>(buildConfig.getDependencies().size());
        for (Map.Entry<ArtifactCoords.Gav, FeaturePackDependencySpec> depEntry : buildConfig.getDependencies().entrySet()) {
            ArtifactCoords depCoords = depEntry.getKey().toArtifactCoords();
            if (depCoords.getVersion() == null) {
                final String coordsStr = artifactVersions.getVersion(depCoords.getGroupId() + ':' + depCoords.getArtifactId());
                if (coordsStr == null) {
                    throw new MojoExecutionException("Failed resolve artifact version for " + depCoords);
                }
                depCoords = ArtifactCoordsUtil.fromJBossModules(coordsStr, "zip");
                if (depCoords.getExtension().equals("pom")) {
                    depCoords = new ArtifactCoords(depCoords.getGroupId(), depCoords.getArtifactId(), depCoords.getVersion(), depCoords.getClassifier(), "zip");
                }
            }
            final Path depZip = resolveArtifact(depCoords);
            final FeaturePackDependencySpec depSpec = depEntry.getValue();
            final FeaturePackConfig depConfig = depSpec.getTarget();

            final FeaturePackDescription fpDescr = FeaturePackDescriber.describeFeaturePackZip(depZip);

            // here we need to determine which format to use to persist the dependency location:
            // Maven coordinates or the FPL. The format is actually set in the feature-pack build parser.
            // However, the parser can't set the actual FPL value, since it does not have enough information.
            // So here we check whether the parser used the Galleon 1 FPL format and if so, replace it with
            // the proper FPL.
            FeaturePackLocation fpl = depEntry.getValue().getTarget().getLocation();
            if(!fpl.isMavenCoordinates()) {
                fpl = fpDescr.getFPID().getLocation();
            } else if(org.apache.commons.lang3.StringUtils.isEmpty(fpl.getBuild())) {
                fpl = fpl.replaceBuild(depCoords.getVersion());
            }
            fpBuilder.addFeaturePackDep(depSpec.getName(), FeaturePackConfig.builder(fpl).init(depConfig).build());
            fpDependencies.put(depSpec.getName(), fpDescr);
        }
    }

    public Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setLocalRepository(session.getLocalRepository());
        buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

        try {
            final ArtifactResult result = artifactResolver.resolveArtifact(buildingRequest,
                    new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getVersion(),
                            "provided", coords.getExtension(), coords.getClassifier(),
                            new DefaultArtifactHandler(coords.getExtension())));
            return result.getArtifact().getFile().toPath();
        } catch (ArtifactResolverException e) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords.toString()), e);
        }
    }

    protected void handleLayers(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        final Path layersDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS);
        if (Files.exists(layersDir)) {
            try (Stream<Path> layers = Files.list(layersDir)) {
                final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
                final Iterator<Path> i = layers.iterator();
                while (i.hasNext()) {
                    final Path layerDir = i.next();
                    Util.findModules(layerDir, moduleXmlByPkgName);
                    if (moduleXmlByPkgName.isEmpty()) {
                        throw new MojoExecutionException("Modules not found in " + layerDir);
                    }
                }
                packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process modules content", e);
            }
        }
        final Path layersConf = srcModulesDir.resolve(WfConstants.LAYERS_CONF);
        if (!Files.exists(layersConf)) {
            return;
        }
        final Path targetPath = getPackagesDir().resolve(WfConstants.LAYERS_CONF).resolve(Constants.CONTENT).resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
        try {
            Files.createDirectories(targetPath.getParent());
            IoUtils.copy(layersConf, targetPath);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(layersConf, targetPath), e);
        }
        final PackageSpec.Builder pkgBuilder = PackageSpec.builder(WfConstants.LAYERS_CONF);
        addPackage(getPackagesDir(), fpBuilder, pkgBuilder);
        fpBuilder.getSpecBuilder().addDefaultPackage(WfConstants.LAYERS_CONF);
    }

    protected void handleAddOns(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        final Path addOnsDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.ADD_ONS);
        if (Files.exists(addOnsDir)) {
            try (Stream<Path> addOn = Files.list(addOnsDir)) {
                final Iterator<Path> i = addOn.iterator();
                while (i.hasNext()) {
                    final Path addOnDir = i.next();
                    Util.findModules(addOnDir, moduleXmlByPkgName);
                    if (moduleXmlByPkgName.isEmpty()) {
                        throw new MojoExecutionException("Modules not found in " + addOnDir);
                    }
                }
                packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process modules content", e);
            }
        }
    }

    protected void handleModules(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        try {
            final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
            Util.findModules(srcModulesDir, moduleXmlByPkgName);
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
    }

    protected PackageSpec addPackage(final Path fpPackagesDir, final FeaturePackDescription.Builder fpBuilder,
            final PackageSpec.Builder pkgBuilder) throws MojoExecutionException {
        final PackageSpec pkg = pkgBuilder.build();
        fpBuilder.addPackage(pkg);
        writeXml(pkg, fpPackagesDir.resolve(pkg.getName()));
        return pkg;
    }

    private void writeXml(PackageSpec pkgSpec, Path dir) throws MojoExecutionException {
        try {
            Util.mkdirs(dir);
            PackageXmlWriter.getInstance().write(pkgSpec, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeFile(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private void packageModules(FeaturePackDescription.Builder fpBuilder,
            Path resourcesDir, Map<String, Path> moduleXmlByPkgName, PackageSpec.Builder modulesAll)
            throws IOException, MojoExecutionException {

        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            final String packageName = module.getKey();
            final Path moduleXml = module.getValue();

            final Path packageDir = getPackagesDir().resolve(packageName);
            final Path targetXml = packageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.MODULE).resolve(resourcesDir.relativize(moduleXml));
            mkdirs(targetXml.getParent());
            IoUtils.copy(moduleXml.getParent(), targetXml.getParent());

            final PackageSpec.Builder pkgSpecBuilder = PackageSpec.builder(packageName);
            final ModuleParseResult parsedModule;
            try {
                parsedModule = ModuleXmlParser.parse(targetXml, WfConstants.UTF8);
                if (!parsedModule.dependencies.isEmpty()) {
                    for (ModuleParseResult.ModuleDependency moduleDep : parsedModule.dependencies) {
                        final ModuleIdentifier moduleId = moduleDep.getModuleId();
                        String depName = moduleId.getName();
                        if (!moduleId.getSlot().equals("main")) {
                            depName += '.' + moduleId.getSlot();
                        }
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            PackageDependencySpec spec = getPackageDepSpec(packageName, moduleXml, moduleDep, depName);
                            if (!spec.isOptional()) {
                                pkgSpecBuilder.addPackageDep(spec);
                            }
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
                            PackageDependencySpec spec = getPackageDepSpec(packageName, moduleXml, moduleDep, depName);
                            if (!spec.isOptional()) {
                                pkgSpecBuilder.addPackageDep(depSrc.getKey(), spec);
                            }
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
            if (modulesAll != null) {
                modulesAll.addPackageDep(packageName, true);
            }
            fpBuilder.addPackage(pkgSpec);
        }
    }

    private static PackageDependencySpec getPackageDepSpec(final String packageName, final Path moduleXml, ModuleParseResult.ModuleDependency moduleDep,
            String depName) throws ParsingException {
        final String passiveValue = moduleDep.getProperty(WfConstants.GALLEON_PASSIVE);
        final PackageDependencySpec depSpec;
        if (passiveValue != null && Boolean.parseBoolean(passiveValue)) {
            if (!moduleDep.isOptional()) {
                throw new ParsingException("Required dependency on module " + packageName + " cannot be annotated as galleon.passive in " + moduleXml);
            }
            depSpec = PackageDependencySpec.passive(depName);
        } else if (moduleDep.isOptional()) {
            depSpec = PackageDependencySpec.optional(depName);
        } else {
            depSpec = PackageDependencySpec.required(depName);
        }
        return depSpec;
    }

    protected void debug(String msg, Object... args) {
        if (getLog().isDebugEnabled()) {
            getLog().debug(format(msg, args));
        }
    }

    protected void warn(String msg, Object... args) {
        if (getLog().isWarnEnabled()) {
            getLog().warn(format(msg, args));
        }
    }

    private void buildArtifactList(ArtifactListBuilder builder) throws MojoExecutionException {
        try {
            final MavenProjectArtifactVersions projectArtifacts = MavenProjectArtifactVersions.getInstance(project);
            Set<String> allArtifacts = new TreeSet<>();
            addHardCodedArtifacts(allArtifacts);
            allArtifacts.addAll(projectArtifacts.getArtifacts().values());
            // Generate the offliner file for dependencies and hardcoded.
            for (String artifact : allArtifacts) {
                ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(artifact, null);
                builder.add(coords);
            }
        } catch (ProvisioningException | IOException | ArtifactDescriptorException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void addHardCodedArtifacts(Set<String> all) throws IOException {
        Path packages = resourcesDir.resolve(Constants.PACKAGES);
        if (Files.exists(packages)) {
            processPackages(fpDir, all);
        }
        final Path projectModules = resourcesDir.resolve(WfConstants.MODULES);
        if (Files.exists(projectModules)) {
            addHardCodedArtifacts(projectModules, all);
        }
    }

    private void processPackages(Path fpDirectory, Set<String> all) throws IOException {
        Files.walkFileTree(fpDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(MODULE_PATH_SEGMENT)) {
                    addHardCodedArtifacts(dir, all);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addHardCodedArtifacts(final Path source, Set<String> all) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (WfConstants.MODULE_XML.equals(file.getFileName().toString())) {
                    try {
                        ModuleXmlVersionResolver.addHardCodedArtifacts(file, all);
                    } catch (XMLStreamException ex) {
                        throw new IOException("Error while reading " + file, ex);
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }
}
