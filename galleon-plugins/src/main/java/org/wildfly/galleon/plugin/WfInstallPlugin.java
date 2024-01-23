/*
 * Copyright 2016-2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import nu.xom.Elements;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.ProvisioningOption;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.diff.FsDiff;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.plugin.InstallPlugin;
import org.jboss.galleon.plugin.ProvisioningPluginWithOptions;
import org.jboss.galleon.progresstracking.ProgressCallback;
import org.jboss.galleon.progresstracking.ProgressTracker;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.universe.FeaturePackLocation.ProducerSpec;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.galleon.plugin.config.CopyArtifact;
import org.wildfly.galleon.plugin.config.CopyPath;
import org.wildfly.galleon.plugin.config.DeletePath;
import org.wildfly.galleon.plugin.config.ExampleFpConfigs;
import org.wildfly.galleon.plugin.config.LineEndingsTask;
import org.wildfly.galleon.plugin.config.XslTransform;

/**
 * WildFly install plugin. Handles all WildFly specifics that occur during provisioning.
 * @author Alexey Loubyansky
 */
public class WfInstallPlugin extends ProvisioningPluginWithOptions implements InstallPlugin {

    // If tooling used for provisioning has wildfly channels setup, the artifacts must be resolved from the channel
    public static final String REQUIRES_CHANNEL_FOR_ARTIFACT_RESOLUTION_PROPERTY = "org.wildfly.plugins.galleon.all.artifact.requires.channel.resolution";
    private static final String TRACK_MODULES_BUILD = "JBMODULES";
    private static final String TRACK_COPY_CONFIGS = "JBCOPYCONFIGS";
    private static final String TRACK_ARTIFACTS_RESOLVE = "JB_ARTIFACTS_RESOLVE";
    private Optional<ArtifactRecorder> artifactRecorder;

    interface ArtifactResolver {
        void resolve(MavenArtifact artifact) throws ProvisioningException;
    }

    private static final String CONFIG_GEN_METHOD = "generate";
    private static final String CONFIG_GEN_GA = "org.wildfly.galleon-plugins:wildfly-config-gen";
    private static final String GALLEON_PLUGINS_GA = "org.wildfly.galleon-plugins:wildfly-galleon-plugins";
    private static final String CONFIG_GEN_CLASS = "org.wildfly.galleon.plugin.config.generator.WfConfigGenerator";
    private static final String CLI_SCRIPT_RUNNER_CLASS = "org.wildfly.galleon.plugin.config.generator.CliScriptRunner";
    private static final String CLI_SCRIPT_RUNNER_METHOD = "runCliScript";
    private static final String JBOSS_MODULES_GA = "org.jboss.modules:jboss-modules";
    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    private static final String WILDFLY_CLI_GA = "org.wildfly.core:wildfly-cli";
    private static final String WILDFLY_LAUNCHER_GA = "org.wildfly.core:wildfly-launcher";

    private static final ProvisioningOption OPTION_MVN_DIST = ProvisioningOption.builder("jboss-maven-dist")
            .setBooleanValueSet()
            .build();
    public static final ProvisioningOption OPTION_DUMP_CONFIG_SCRIPTS = ProvisioningOption.builder("jboss-dump-config-scripts").setPersistent(false).build();
    private static final ProvisioningOption OPTION_FORK_EMBEDDED = ProvisioningOption.builder("jboss-fork-embedded")
            .setBooleanValueSet()
            .build();

    /**
     * If present, indicates whether the existing System Properties will be reset to the default set provided by
     * ForkedEmbeddedUtil.RESETTABLE_EMBEDDED_SYS_PROPERTIES. The format of this configuration is a comma separated list of
     * system properties to add or remove from this default Set.
     *
     * If the property starts with '-', it means the property will be removed from the set, otherwise, the property will be added.
     * Values are added or removed from the default Set in the same order as they have been specified in this configuration option.
     *
     * @see @see org.wildfly.galleon.plugin.server.ForkedEmbeddedUtil
     */
    private static final ProvisioningOption OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES = ProvisioningOption.builder("jboss-reset-embedded-system-properties")
            .build();

    private static final ProvisioningOption OPTION_MVN_REPO = ProvisioningOption.builder("jboss-maven-repo")
            .setPersistent(false)
            .build();
    private static final ProvisioningOption OPTION_OVERRIDDEN_ARTIFACTS = ProvisioningOption.builder("jboss-overridden-artifacts").setPersistent(true).build();
    private static final ProvisioningOption OPTION_BULK_RESOLVE_ARTIFACTS = ProvisioningOption.builder("jboss-bulk-resolve-artifacts").setBooleanValueSet().build();
    private static final ProvisioningOption OPTION_RECORD_ARTIFACTS = ProvisioningOption.builder("jboss-resolved-artifacts-cache")
            .setDefaultValue(".installation" + File.separator + ".cache")
            .build();
    private ProvisioningRuntime runtime;
    MessageWriter log;

    private Map<String, String> mergedArtifactVersions = new HashMap<>();
    private final Map<String, String> overriddenArtifactVersions = new HashMap<>();
    private Map<ProducerSpec, Map<String, String>> fpArtifactVersions = new HashMap<>();
    private Map<ProducerSpec, Map<String, String>> fpTasksProps = Collections.emptyMap();
    private Map<String, String> mergedTaskProps = new HashMap<>();
    private PropertyResolver mergedTaskPropsResolver;

    private boolean thinServer;

    private Set<String> schemaGroups = Collections.emptySet();

    private List<WildFlyPackageTask> finalizingTasks = Collections.emptyList();
    private List<PackageRuntime> finalizingTasksPkgs = Collections.emptyList();

    private DocumentBuilderFactory docBuilderFactory;
    private TransformerFactory xsltFactory;
    private Map<String, Transformer> xslTransformers = Collections.emptyMap();

    private Map<FPID, ExampleFpConfigs> exampleConfigs = Collections.emptyMap();

    private ProgressTracker<PackageRuntime> pkgProgressTracker;

    private MavenRepoManager maven;

    private Map<Path, PackageRuntime> jbossModules = new LinkedHashMap<>();

    private Path generatedMavenRepo;

    private AbstractArtifactInstaller artifactInstaller;
    private ArtifactResolver artifactResolver;
    private boolean channelArtifactResolution;

    private boolean bulkResolveArtifacts;

    private final Map<MavenArtifact, MavenArtifact> artifactCache = new HashMap<>();
    private final Map<Path, ModuleTemplate> moduleTemplateCache = new HashMap<>();

    private final Map<String, String> resolvedVersionsProperties = new HashMap<>();
    private Map<ProducerSpec, WildFlyChannelResolutionMode> channelResolutionModes = new LinkedHashMap<>();
    private Map<String, ProducerSpec> gaToProducer = new HashMap<>();

    @Override
    protected List<ProvisioningOption> initPluginOptions() {
        return Arrays.asList(OPTION_MVN_DIST, OPTION_DUMP_CONFIG_SCRIPTS,
                             OPTION_FORK_EMBEDDED, OPTION_MVN_REPO,
                             OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES,
                             OPTION_OVERRIDDEN_ARTIFACTS, OPTION_BULK_RESOLVE_ARTIFACTS,
                             OPTION_RECORD_ARTIFACTS);
    }

    public ProvisioningRuntime getRuntime() {
        return runtime;
    }

    private boolean isThinServer() throws ProvisioningException {
        return getBooleanOption(OPTION_MVN_DIST);
    }

    private Path getGeneratedMavenRepo() throws ProvisioningException {
        if (!runtime.isOptionSet(OPTION_MVN_REPO)) {
            return null;
        }
        final String value = runtime.getOptionValue(OPTION_MVN_REPO);
        return value == null ? null : Paths.get(value);
    }

    private Map<String, String> getOverriddenArtifacts() throws ProvisioningException {
        if (!runtime.isOptionSet(OPTION_OVERRIDDEN_ARTIFACTS)) {
            return Collections.emptyMap();
        }
        if (channelArtifactResolution) {
            throw new ProvisioningException("Option " + OPTION_OVERRIDDEN_ARTIFACTS + " can't be used when channels are enabled.");
        }
        final String value = runtime.getOptionValue(OPTION_OVERRIDDEN_ARTIFACTS);
        return value == null ? Collections.emptyMap() : Utils.toArtifactsMap(value);
    }

    private boolean isBulkResolveArtifacts() throws ProvisioningException {
        return getBooleanOption(OPTION_BULK_RESOLVE_ARTIFACTS);
    }

    private boolean isForkEmbedded(ProvisioningRuntime runtime) throws ProvisioningException {
        return getBooleanOption(OPTION_FORK_EMBEDDED);
    }

    private String isResetEmbeddedSystemProperties() throws ProvisioningException {
        if (!runtime.isOptionSet(OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES)) {
            return null;
        }
        final String value = runtime.getOptionValue(OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES);
        return value == null ? "" : value;
    }

    private String getStabilityLevel() throws ProvisioningException {
        final String value = runtime.getLowestStability();
        return value == null ? "" : value;
    }

    private boolean getBooleanOption(ProvisioningOption option) throws ProvisioningException {
        if (!runtime.isOptionSet(option)) {
            return false;
        }
        final String value = runtime.getOptionValue(option);
        return value == null ? true : Boolean.parseBoolean(value);
    }

    @Override
    public void preInstall(ProvisioningRuntime runtime) throws ProvisioningException {
        final FsDiff fsDiff = runtime.getFsDiff();
        if(fsDiff == null) {
            return;
        }
        final String runningMode = fsDiff.getEntry("standalone/tmp/startup-marker") != null ? WfConstants.STANDALONE
                : fsDiff.getEntry("domain/tmp/startup-marker") != null ? WfConstants.DOMAIN : null;
        if (runningMode != null) {
            throw new ProvisioningException("The server appears to be running (" + runningMode + " mode).");
        }
    }

    /* (non-Javadoc)
     * @see org.jboss.galleon.util.plugin.ProvisioningPlugin#execute()
     */
    @Override
    public void postInstall(ProvisioningRuntime runtime) throws ProvisioningException {
        final long startTime = runtime.isLogTime() ? System.nanoTime() : -1;
        this.runtime = runtime;
        log = runtime.getMessageWriter();
        log.verbose("WildFly Galleon Installation Plugin");

        if (runtime.isOptionSet(OPTION_RECORD_ARTIFACTS)) {
            final String pathValue = runtime.getOptionValue(OPTION_RECORD_ARTIFACTS);
            if (pathValue != null && !pathValue.isEmpty()) {
                try {
                    log.verbose("Starting artifact log");
                    artifactRecorder = Optional.of(new ArtifactRecorder(runtime.getStagedDir(), Path.of(pathValue)));
                } catch (IOException e) {
                    throw new ProvisioningException("Unable to create artifact.log", e);
                }
            }
        } else {
            artifactRecorder = Optional.empty();
        }

        this.bulkResolveArtifacts = isBulkResolveArtifacts();

        thinServer = isThinServer();
        generatedMavenRepo = getGeneratedMavenRepo();
        if (generatedMavenRepo != null) {
            IoUtils.recursiveDelete(generatedMavenRepo);
        }
        maven = (MavenRepoManager) runtime.getArtifactResolver(MavenRepoManager.REPOSITORY_ID);
        // The Channel resolution depends on the tool in use.
        // Generic Galleon provisioning doesn't support it.
        try {
            Class<?> clazz = Class.forName("org.wildfly.channel.spi.ChannelResolvable");
            channelArtifactResolution = clazz.isAssignableFrom(maven.getClass());
        } catch(ClassNotFoundException ex) {
            log.verbose("Channel not present in classpath.");
        }
        log.verbose("Channel artifact resolution enabled=" + channelArtifactResolution);
        // Overridden artifacts
        overriddenArtifactVersions.putAll(getOverriddenArtifacts());
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }

            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            if(Files.exists(artifactProps)) {
                final Map<String, String> versionProps = Utils.readProperties(artifactProps);
                for (Entry<String, String> entry : overriddenArtifactVersions.entrySet()) {
                    if (versionProps.containsKey(entry.getKey())) {
                        versionProps.put(entry.getKey(), entry.getValue());
                    }
                }
                fpArtifactVersions.put(fp.getFPID().getProducer(), versionProps);
                // Handle artifacts that are directly resolved from the plugin
                // org.wildfly.core:wildfly-launcher
                // org.jboss.modules:jboss-modules
                // org.wildfly.core:wildfly-cli
                // org.wildfly.galleon-plugins:wildfly-config-gen
                // org.wildfly.galleon-plugins:wildfly-galleon-plugins
                if (versionProps.containsKey(CONFIG_GEN_GA)) {
                    gaToProducer.put(CONFIG_GEN_GA, fp.getFPID().getProducer());
                }
                if (versionProps.containsKey(GALLEON_PLUGINS_GA)) {
                    gaToProducer.put(GALLEON_PLUGINS_GA, fp.getFPID().getProducer());
                }
                if (versionProps.containsKey(WILDFLY_CLI_GA)) {
                    gaToProducer.put(WILDFLY_CLI_GA, fp.getFPID().getProducer());
                }
                if (versionProps.containsKey(WILDFLY_LAUNCHER_GA)) {
                    gaToProducer.put(WILDFLY_LAUNCHER_GA, fp.getFPID().getProducer());
                }
                if (versionProps.containsKey(JBOSS_MODULES_GA)) {
                    gaToProducer.put(JBOSS_MODULES_GA, fp.getFPID().getProducer());
                }
                mergedArtifactVersions.putAll(versionProps);
            }

            final Path tasksPropsPath = wfRes.resolve(WfConstants.WILDFLY_TASKS_PROPS);
            if(Files.exists(tasksPropsPath)) {
                final Map<String, String> fpProps = Utils.readProperties(tasksPropsPath);
                fpTasksProps = CollectionUtils.put(fpTasksProps, fp.getFPID().getProducer(), fpProps);
                mergedTaskProps.putAll(fpProps);
            }

            final Path channelsPropsPath = wfRes.resolve(WfConstants.WILDFLY_CHANNEL_PROPS);
            if(Files.exists(channelsPropsPath)) {
                final Map<String, String> channelProps = Utils.readProperties(channelsPropsPath);
                String mode = channelProps.get(WfConstants.WILDFLY_CHANNEL_RESOLUTION_PROP);
                if (mode != null) {
                    channelResolutionModes = CollectionUtils.put(channelResolutionModes, fp.getFPID().getProducer(), WildFlyChannelResolutionMode.valueOf(mode));
                }
            }

            if(fp.containsPackage(WfConstants.DOCS_SCHEMA)) {
                final Path schemaGroupsTxt = fp.getPackage(WfConstants.DOCS_SCHEMA).getResource(
                        WfConstants.PM, WfConstants.WILDFLY, WfConstants.SCHEMA_GROUPS_TXT);
                try(BufferedReader reader = Files.newBufferedReader(schemaGroupsTxt)) {
                    String line = reader.readLine();
                    while(line != null) {
                        schemaGroups = CollectionUtils.add(schemaGroups, line);
                        line = reader.readLine();
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(schemaGroupsTxt), e);
                }
            }
        }
        // Check that all overridden artifacts are actually known.
        for (String key : overriddenArtifactVersions.keySet()) {
            if (!mergedArtifactVersions.containsKey(key)) {
                throw new ProvisioningException("Overridden artifacts " + key + " is not found in the set of known server artifacts");
            }
        }
        mergedArtifactVersions.putAll(overriddenArtifactVersions);
        mergedTaskPropsResolver = new MapPropertyResolver(mergedTaskProps);

        // We must create resolver and installer at this point, prior to process the packges.
        // The CopyArtifact tasks could need the resolver and installer we are instantiating there.
        artifactResolver = this::resolveMaven;
        artifactInstaller = new SimpleArtifactInstaller(artifactResolver, generatedMavenRepo, artifactRecorder);

        // Resolution of provisioning artifacts that we would need in the generated licenses.
        MavenArtifact configGen = Utils.toArtifactCoords(mergedArtifactVersions, CONFIG_GEN_GA,
                false, channelArtifactResolution, requireChannel(gaToProducer.get(CONFIG_GEN_GA)));
        artifactResolver.resolve(configGen);
        MavenArtifact plugin = Utils.toArtifactCoords(mergedArtifactVersions, GALLEON_PLUGINS_GA,
                false, channelArtifactResolution, requireChannel(gaToProducer.get(GALLEON_PLUGINS_GA)));
        artifactResolver.resolve(plugin);

        final ProvisioningLayoutFactory layoutFactory = runtime.getLayout().getFactory();
        pkgProgressTracker = layoutFactory.getProgressTracker(ProvisioningLayoutFactory.TRACK_PACKAGES);
        long pkgsTotal = 0;
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            pkgsTotal += fp.getPackageNames().size();
        }
        pkgProgressTracker.starting(pkgsTotal);
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            processPackages(fp);
        }
        pkgProgressTracker.complete();
        if (!jbossModules.isEmpty()) {

            if (bulkResolveArtifacts) {
                log.verbose("Preloading artifacts");
                final ProgressTracker<MavenArtifact> artifactTracker = layoutFactory.getProgressTracker(TRACK_ARTIFACTS_RESOLVE);
                populateArtifactCache();
                artifactTracker.starting(artifactCache.size());
                resolveArtifactsInCache(artifactTracker);
                artifactTracker.complete();
                log.verbose("Finished preloading artifacts");
            }

            final ProgressTracker<PackageRuntime> modulesTracker = layoutFactory.getProgressTracker(TRACK_MODULES_BUILD);
            modulesTracker.starting(jbossModules.size());

            for (Map.Entry<Path, PackageRuntime> entry : jbossModules.entrySet()) {
                final PackageRuntime pkg = entry.getValue();
                modulesTracker.processing(pkg);
                try {
                    processModuleTemplate(pkg, entry.getKey());
                } catch (IOException e) {
                    throw new ProvisioningException("Failed to process JBoss module XML template for feature-pack "
                            + pkg.getFeaturePackRuntime().getFPID() + " package " + pkg.getName(), e);
                }
                modulesTracker.processed(pkg);
            }
            modulesTracker.complete();
        }

        final Path layersConf = runtime.getStagedDir().resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
        if (Files.exists(layersConf)) {
            mergeLayerConfs(runtime);
        }

         generateConfigs(runtime);

        // If the dir doesn't exist, no configuration has been generated, no need to execute CLI scripts.
        if (Files.exists(runtime.getStagedDir())) {
            for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
                final Path finalizeCli = fp.getResource(WfConstants.WILDFLY, WfConstants.SCRIPTS, "finalize.cli");
                if (Files.exists(finalizeCli)) {
                    final URL[] cp = new URL[2];
                    try {
                        MavenArtifact artifact = Utils.toArtifactCoords(mergedArtifactVersions, CONFIG_GEN_GA,
                                false, channelArtifactResolution, requireChannel(gaToProducer.get(CONFIG_GEN_GA)));
                        artifactResolver.resolve(artifact);
                        cp[0] = artifact.getPath().toUri().toURL();
                        artifact = Utils.toArtifactCoords(mergedArtifactVersions, WILDFLY_LAUNCHER_GA,
                                false, channelArtifactResolution, requireChannel(gaToProducer.get(WILDFLY_LAUNCHER_GA)));
                        artifactResolver.resolve(artifact);
                        cp[1] = artifact.getPath().toUri().toURL();
                    } catch (IOException e) {
                        throw new ProvisioningException("Failed to init classpath to run CLI finalize script for " + runtime.getStagedDir(), e);
                    }
                    final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
                    final URLClassLoader cliScriptCl = new URLClassLoader(cp, originalCl);
                    Path script;
                    try {
                        try {
                            byte[] content = Files.readAllBytes(finalizeCli);
                            Path tmpDir = runtime.getTmpPath();
                            if (!Files.exists(tmpDir)) {
                                Files.createDirectory(tmpDir);
                            }
                            script = tmpDir.resolve(finalizeCli.getFileName().toString());
                            Files.write(script, content);
                        } catch (IOException ex) {
                            throw new ProvisioningException(ex.getLocalizedMessage(), ex);
                        }
                        Thread.currentThread().setContextClassLoader(cliScriptCl);
                        try {
                            final Class<?> cliScriptRunnerCls = cliScriptCl.loadClass(CLI_SCRIPT_RUNNER_CLASS);
                            final Method m = cliScriptRunnerCls.getMethod(CLI_SCRIPT_RUNNER_METHOD, Path.class, Path.class, MessageWriter.class);
                            m.invoke(null, runtime.getStagedDir(), script, log);
                        } catch (Throwable e) {
                            throw new ProvisioningException("Failed to initialize CLI script runner " + CLI_SCRIPT_RUNNER_CLASS, e);
                        }
                    } finally {
                        Thread.currentThread().setContextClassLoader(originalCl);
                        try {
                            cliScriptCl.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
        }

        if(!finalizingTasks.isEmpty()) {
            for(int i = 0; i < finalizingTasks.size(); ++i) {
                finalizingTasks.get(i).execute(this, finalizingTasksPkgs.get(i));
            }
        }

        if(!exampleConfigs.isEmpty()) {
            provisionExampleConfigs();
        }

        if (artifactRecorder.isPresent()) {
            try {
                artifactRecorder.get().writeCacheManifest();
            } catch (IOException e) {
                throw new ProvisioningException("Unable to record provisioned artifacts", e);
            }
        }

        if (startTime > 0) {
            log.print(Errors.tookTime("Overall WildFly Galleon Plugin", startTime));
        }
    }

    private void populateArtifactCache() throws ProvisioningException {
        for (Entry<Path, PackageRuntime> entry : jbossModules.entrySet()) {
            final PackageRuntime pkg = entry.getValue();
            try {
                findArtifacts(pkg, entry.getKey());
            } catch (IOException e) {
                throw new ProvisioningException("Failed to process JBoss module XML template for feature-pack "
                                                   + pkg.getFeaturePackRuntime().getFPID() + " package " + pkg.getName(), e);
            }
        }
    }

    private void findArtifacts(PackageRuntime pkg, Path moduleXmlRelativePath) throws ProvisioningException, IOException {
        final Path moduleTemplateFile = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY, WfConstants.MODULE).resolve(moduleXmlRelativePath);
        final Path targetPath = runtime.getStagedDir().resolve(moduleXmlRelativePath.toString());
        final Map<String, String> versionProps = fpArtifactVersions.get(pkg.getFeaturePackRuntime().getFPID().getProducer());
        ModuleTemplate moduleTemplate = new ModuleTemplate(pkg, moduleTemplateFile, targetPath);
        moduleTemplateCache.put(moduleTemplateFile, moduleTemplate);
        if (!moduleTemplate.isModule()) {
            return;
        }

        final Elements artifacts = moduleTemplate.getArtifacts();
        if (artifacts == null) {
            return;
        }

        final int artifactCount = artifacts.size();
        for (int i = 0; i < artifactCount; i++) {
            final AbstractModuleTemplateProcessor.ModuleArtifact moduleArtifact = new AbstractModuleTemplateProcessor.ModuleArtifact(moduleTemplate,
                    artifacts.get(i), versionProps, log, artifactInstaller, channelArtifactResolution,
                    requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()));
            final MavenArtifact mavenArtifact = moduleArtifact.getUnresolvedArtifact();
            if (mavenArtifact != null) {
                final MavenArtifact key = new MavenArtifact();
                key.setGroupId(mavenArtifact.getGroupId());
                key.setArtifactId(mavenArtifact.getArtifactId());
                key.setExtension(mavenArtifact.getExtension());
                key.setClassifier(mavenArtifact.getClassifier());
                key.setVersion(mavenArtifact.getVersion());
                key.setVersionRange(mavenArtifact.getVersionRange());

                artifactCache.put(key, mavenArtifact);
            }
        }
    }

    private void resolveArtifactsInCache(ProgressTracker<MavenArtifact> tracker) throws ProvisioningException {
        try {
            maven.resolveAll(addListener(artifactCache.values(), tracker));
        } catch (MavenUniverseException e) {
            throw new ProvisioningException("Failed to resolve artifact", e);
        }
    }

    private Collection<MavenArtifact> addListener(Collection<MavenArtifact> values, ProgressTracker<MavenArtifact> tracker) {
        return values.stream().map((a)->new MonitorableArtifact(a, tracker)).collect(Collectors.toList());
    }

    private void setupLayerDirectory(Path layersConf, Path layersDir) throws ProvisioningException {
        log.verbose("Creating layers directories if needed.");
        try (BufferedReader reader = Files.newBufferedReader(layersConf)) {
            Properties props = new Properties();
            props.load(reader);
            String layersProp = props.getProperty(WfConstants.LAYERS);
            if (layersProp == null || (layersProp = layersProp.trim()).length() == 0) {
                return;
            }
            final String[] layerNames = layersProp.split(",");
            for (String layerName : layerNames) {
                log.verbose("Found layer %s", layerName);
                Path layerDir = layersDir.resolve(layerName);
                if (!Files.exists(layerDir)) {
                    log.verbose("Creating directory %s", layerDir);
                    Files.createDirectories(layerDir);
                }
            }
        } catch (IOException ex) {
            throw new ProvisioningException("Failed to setup layers directory in " + layersDir, ex);
        }
    }

    private void mergeLayerConfs(ProvisioningRuntime runtime) throws ProvisioningException {
        final List<Path> layersConfs = Utils.collectLayersConf(runtime.getLayout());
        // The list contains all layer confs, even the one that will be not provisioned.
        // create directories for all of them.
        for (Path p : layersConfs) {
            setupLayerDirectory(p, runtime.getStagedDir().resolve(WfConstants.MODULES).
                    resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS));
        }
        if(layersConfs.size() < 2) {
            return;
        }
        Utils.mergeLayersConfs(layersConfs, runtime.getStagedDir());
    }

    private void provisionExampleConfigs() throws ProvisioningException {

        final Path examplesTmp = runtime.getTmpPath("example-configs");
        final ProvisioningLayoutFactory factory = runtime.getLayout().getFactory();
        final ProgressTracker<List<Object>> examplesTracker = factory.getProgressTracker("JBEXTRACONFIGS");
        final List<String> trackedPhases = new ArrayList<>(List.of(ProvisioningLayoutFactory.TRACK_LAYOUT_BUILD, ProvisioningLayoutFactory.TRACK_PACKAGES,
                TRACK_MODULES_BUILD, ProvisioningLayoutFactory.TRACK_CONFIGS));
        if (isBulkResolveArtifacts()) {
            trackedPhases.add(2, TRACK_ARTIFACTS_RESOLVE);
        }
        final ProgressCallback<Object> aggregatingCallback = new ProgressCallback<>() {
            private int counter = 0;
            @Override
            public void processing(ProgressTracker<Object> progressTracker) {
                Object item = progressTracker.getItem();
                examplesTracker.processing(Arrays.asList(trackedPhases.get(counter),item));
            }

            @Override
            public void pulse(ProgressTracker<Object> progressTracker) {

            }

            @Override
            public void complete(ProgressTracker<Object> progressTracker) {
                Object item = progressTracker.getItem();
                examplesTracker.processed(Arrays.asList(trackedPhases.get(counter),item));
                counter++;
            }

            @Override
            public void starting(ProgressTracker<Object> pt) {
            }
        };
        trackedPhases.forEach((p)->factory.setProgressCallback(p, aggregatingCallback));

        final ProvisioningManager pm = ProvisioningManager.builder()
                .setInstallationHome(examplesTmp)
                .setMessageWriter(log)
                .setLayoutFactory(factory)
                .setRecordState(false)
                .build();

        List<Path> configPaths = new ArrayList<>();
        final ProvisioningConfig.Builder configBuilder = ProvisioningConfig.builder();
        for(Map.Entry<FPID, ExampleFpConfigs> example : exampleConfigs.entrySet()) {
            final FeaturePackConfig.Builder fpBuilder = FeaturePackConfig.builder(example.getKey().getLocation())
                    .setInheritConfigs(false)
                    .setInheritPackages(false);
            final ExampleFpConfigs fpExampleConfigs = example.getValue();
            if(fpExampleConfigs != null) {
                for(Map.Entry<ConfigId, ConfigModel> config : fpExampleConfigs.getConfigs().entrySet()) {
                    final ConfigId configId = config.getKey();
                    final ConfigModel configModel = config.getValue();
                    String configName = null;
                    if(configModel != null) {
                        fpBuilder.addConfig(configModel);
                        if(configModel.hasProperties()) {
                            if(WfConstants.STANDALONE.equals(configId.getModel())) {
                                configName = configModel.getProperties().get(WfConstants.EMBEDDED_ARG_SERVER_CONFIG);
                            } else if(WfConstants.HOST.equals(configId.getModel())) {
                                configName = configModel.getProperties().get(WfConstants.EMBEDDED_ARG_HOST_CONFIG);
                            } else {
                                configName = configModel.getProperties().get(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG);
                            }
                        }
                        if(configName == null) {
                            configName = configId.getName();
                        }
                    } else {
                        fpBuilder.includeDefaultConfig(configId);
                        configName = configId.getName();
                    }
                    if(WfConstants.HOST.equals(configId.getModel())) {
                        configPaths.add(examplesTmp.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION).resolve(configName));
                    } else {
                        configPaths.add(examplesTmp.resolve(configId.getModel()).resolve(WfConstants.CONFIGURATION).resolve(configName));
                    }
                }
            }
            configBuilder.addFeaturePackDep(fpBuilder.build());
        }
        try {
            log.verbose("Generating example configs");
            ProvisioningConfig config = configBuilder.build();
            Map<String, String> options = runtime.getLayout().getOptions();
            if (!options.containsKey(OPTION_MVN_DIST.getName()) || options.containsKey(OPTION_MVN_REPO.getName())) {
                final Map<String, String> tmp = new HashMap<>(options.size() + 1);
                tmp.putAll(options);
                options = tmp;
                options.put(OPTION_MVN_DIST.getName(), null);
                // Remove OPTION_MVN_REPO so we don't waste time populating it again
                // as it was already populated by the main postInstall provisioning.
                // It would be a waste of time regardless, but if jakartaTransform is true,
                // trying to populate it again will fail provisioning
                options.remove(OPTION_MVN_REPO.getName());
            }

            pm.provision(config, options);
        } catch(ProvisioningException e) {
            throw new ProvisioningException("Failed to generate example configs", e);
        }

        final Path exampleConfigsDir = runtime.getStagedDir().resolve(WfConstants.DOCS).resolve("examples").resolve("configs");
        for(Path configPath : configPaths) {
            examplesTracker.processing(Arrays.asList(TRACK_COPY_CONFIGS, configPath));
            try {
                IoUtils.copy(configPath, exampleConfigsDir.resolve(configPath.getFileName()));
            } catch (IOException e) {
                throw new ProvisioningException(Errors.copyFile(configPath, exampleConfigsDir.resolve(configPath.getFileName())), e);
            }
        }
        examplesTracker.complete();
    }

    private void generateConfigs(ProvisioningRuntime runtime) throws ProvisioningException {
        if(!runtime.hasConfigs()) {
            return;
        }

        final long startTime = runtime.isLogTime() ? System.nanoTime() : -1;

        final URL[] cp = new URL[3];
        try {
            MavenArtifact artifact = Utils.toArtifactCoords(mergedArtifactVersions, CONFIG_GEN_GA,
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(CONFIG_GEN_GA)));
            artifactResolver.resolve(artifact);
            if (artifactRecorder.isPresent()) {
                artifactRecorder.get().cache(artifact, artifact.getPath());
            }
            cp[0] = artifact.getPath().toUri().toURL();
            artifact = Utils.toArtifactCoords(mergedArtifactVersions, JBOSS_MODULES_GA,
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(JBOSS_MODULES_GA)));
            artifactResolver.resolve(artifact);
            cp[1] = artifact.getPath().toUri().toURL();
            artifact = Utils.toArtifactCoords(mergedArtifactVersions, WILDFLY_CLI_GA+"::client",
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(WILDFLY_CLI_GA)));
            artifactResolver.resolve(artifact);
            cp[2] = artifact.getPath().toUri().toURL();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to init classpath for " + runtime.getStagedDir(), e);
        }
        if(log.isVerboseEnabled()) {
            log.verbose("Config generator classpath:");
            for(int i = 0; i < cp.length; ++i) {
                log.verbose(i+1 + ". " + cp[i]);
            }
        }

        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final URLClassLoader configGenCl = new URLClassLoader(cp, originalCl);
        Thread.currentThread().setContextClassLoader(configGenCl);
        try {
            final Class<?> configHandlerCls = configGenCl.loadClass(CONFIG_GEN_CLASS);
            final Constructor<?> ctor = configHandlerCls.getConstructor();
            final Object generator = ctor.newInstance();
            final boolean forkEmbedded = isForkEmbedded(runtime);
            final String resetEmbeddedSystemProperties = isResetEmbeddedSystemProperties();
            final String stabilityLevel = getStabilityLevel();
            invokeConfigGenerator(configHandlerCls, generator, forkEmbedded, resetEmbeddedSystemProperties, stabilityLevel);
            if(startTime > 0) {
                log.print(Errors.tookTime("WildFly configuration generation", startTime));
            }
        } catch(InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if(cause instanceof ProvisioningException) {
                throw (ProvisioningException)cause;
            } else {
                throw new ProvisioningException("Failed to invoke config generator " + CONFIG_GEN_CLASS, cause);
            }
        } catch (Throwable e) {
            throw new ProvisioningException("Failed to initialize config generator " + CONFIG_GEN_CLASS, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            try {
                configGenCl.close();
            } catch (IOException e) {
            }
        }
    }

    private void processPackages(final FeaturePackRuntime fp) throws ProvisioningException {
        log.verbose("Processing %s packages", fp.getFPID());
        for(PackageRuntime pkg : fp.getPackages()) {
            pkgProgressTracker.processing(pkg);
            final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
            if(!Files.exists(pmWfDir)) {
                pkgProgressTracker.processed(pkg);
                continue;
            }
            final Path moduleDir = pmWfDir.resolve(WfConstants.MODULE);
            if(Files.exists(moduleDir)) {
                processModules(pkg, moduleDir);
            }
            final Path tasksXml = pmWfDir.resolve(WfConstants.TASKS_XML);
            if (Files.exists(tasksXml)) {
                final WildFlyPackageTasks pkgTasks = WildFlyPackageTasks.load(tasksXml);
                if (pkgTasks.hasTasks()) {
                    log.verbose("Processing %s package %s tasks", fp.getFPID(), pkg.getName());
                    for (WildFlyPackageTask task : pkgTasks.getTasks()) {
                        if (task.getPhase() == WildFlyPackageTask.Phase.PROCESSING) {
                            task.execute(this, pkg);
                        } else {
                            finalizingTasks = CollectionUtils.add(finalizingTasks, task);
                            finalizingTasksPkgs = CollectionUtils.add(finalizingTasksPkgs, pkg);
                        }
                    }
                }
                if (pkgTasks.hasMkDirs()) {
                    mkdirs(pkgTasks, this.runtime.getStagedDir());
                }

                final List<WildFlyPackageTask> finalizingLineEndingTasks = pkgTasks.getLineEndings().stream().filter(t -> t.getPhase() == WildFlyPackageTask.Phase.FINALIZING).collect(Collectors.toList());

                finalizingTasks = CollectionUtils.addAll(finalizingTasks, finalizingLineEndingTasks);
                for (int i=0; i< finalizingLineEndingTasks.size(); i++) {
                    finalizingTasksPkgs = CollectionUtils.add(finalizingTasksPkgs, pkg);
                }

                final List<LineEndingsTask> processingLineEndingTasks = pkgTasks.getLineEndings().stream().filter(t -> t.getPhase() == WildFlyPackageTask.Phase.PROCESSING).collect(Collectors.toList());
                for (LineEndingsTask lineEnding : processingLineEndingTasks) {
                    lineEnding.execute(this, pkg);
                }
            }
            pkgProgressTracker.processed(pkg);
        }
    }

    public void xslTransform(PackageRuntime pkg, XslTransform xslt) throws ProvisioningException {

        final Path src = runtime.getStagedDir().resolve(xslt.getSrc());
        if (!Files.exists(src)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(src));
        }
        // Copy the path to handle replacements
        Path tmp = runtime.getTmpPath().resolve(src.getFileName());
        try {
            PropertyResolver versionsResolver = new MapPropertyResolver(resolvedVersionsProperties);
            PropertyReplacer.copy(src, tmp, versionsResolver, "Not Installed");
            Files.copy(tmp, src, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new ProvisioningException(ex);
        }
        final Path output = runtime.getStagedDir().resolve(xslt.getOutput());
        if (Files.exists(output)) {
            throw new ProvisioningException(Errors.pathAlreadyExists(output));
        }

        try (InputStream srcInput = Files.newInputStream(src); OutputStream outStream = Files.newOutputStream(output)) {
            final org.w3c.dom.Document document = getXmlDocumentBuilderFactory().newDocumentBuilder().parse(srcInput);
            final Transformer transformer = getXslTransformer(xslt.getStylesheet());
            if (xslt.hasParams()) {
                for (Map.Entry<String, String> param : xslt.getParams().entrySet()) {
                    transformer.setParameter(param.getKey(), param.getValue());
                }
            }
            final Map<String, String> taskProps = xslt.isFeaturePackProperties() ? fpTasksProps.get(pkg.getFeaturePackRuntime().getFPID().getProducer()) : mergedTaskProps;
            if (taskProps != null) {
                for (Map.Entry<String, String> prop : taskProps.entrySet()) {
                    transformer.setParameter(prop.getKey(), prop.getValue());
                }
            }
            final DOMSource source = new DOMSource(document);
            final StreamResult result = new StreamResult(outStream);
            transformer.transform(source, result);
        } catch (ProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new ProvisioningException(
                    "Failed to transform " + xslt.getSrc() + " with " + xslt.getStylesheet() + " to " + xslt.getOutput(), e);
        }
    }

    private Transformer getXslTransformer(String stylesheet) throws ProvisioningException {
        Transformer transformer = xslTransformers.get(stylesheet);
        if(transformer != null) {
            return transformer;
        }
        transformer = getXslTransformer(runtime.getStagedDir().resolve(stylesheet));
        xslTransformers = CollectionUtils.put(xslTransformers, stylesheet, transformer);
        return transformer;
    }

    public DocumentBuilderFactory getXmlDocumentBuilderFactory() {
        if(docBuilderFactory == null) {
            docBuilderFactory = DocumentBuilderFactory.newInstance();
        }
        return docBuilderFactory;
    }

    public Transformer getXslTransformer(Path p) throws ProvisioningException {
        if(!Files.exists(p)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(p));
        }
        try (InputStream styleInput = Files.newInputStream(p)) {
            final StreamSource stylesource = new StreamSource(styleInput);
            if(xsltFactory == null) {
                xsltFactory = TransformerFactory.newInstance();
            }
            return xsltFactory.newTransformer(stylesource);
        } catch (Exception e) {
            throw new ProvisioningException("Failed to initialize a transformer for " + p, e);
        }
    }

    private void processModules(PackageRuntime pkg, Path fpModuleDir) throws ProvisioningException {
        try {
            final Path stagedDir = runtime.getStagedDir();
            if(!Files.exists(stagedDir)) {
                Files.createDirectories(stagedDir);
            }
            Files.walkFileTree(fpModuleDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                    final Path targetDir = stagedDir.resolve(fpModuleDir.relativize(dir).toString());
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
                    if(file.getFileName().toString().equals(WfConstants.MODULE_XML)) {
                        final PackageRuntime overriddenPkg = jbossModules.put(fpModuleDir.relativize(file), pkg);
                        if (overriddenPkg != null) {
                            if(log.isVerboseEnabled()) {
                                log.verbose("Feature-pack " + pkg.getFeaturePackRuntime().getFPID() + " package " + pkg.getName() +
                                " override jboss-module from feature-pack " + overriddenPkg.getFeaturePackRuntime().getFPID() +
                                " package " + overriddenPkg.getName());
                            }
                        }
                    } else {
                        Files.copy(file, stagedDir.resolve(fpModuleDir.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProvisioningException("Failed to process modules from package " + pkg.getName() + " from feature-pack " + pkg.getFeaturePackRuntime().getFPID(), e);
        }
    }

    private void processModuleTemplate(PackageRuntime pkg, Path moduleXmlRelativePath) throws ProvisioningException, IOException {
        final Path moduleTemplateFile = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY, WfConstants.MODULE).resolve(moduleXmlRelativePath);
        final Path targetPath = runtime.getStagedDir().resolve(moduleXmlRelativePath.toString());

        final ModuleTemplate moduleTemplate;
        if (moduleTemplateCache.containsKey(moduleTemplateFile)) {
            moduleTemplate = moduleTemplateCache.get(moduleTemplateFile);
        } else {
            moduleTemplate = new ModuleTemplate(pkg, moduleTemplateFile, targetPath);
        }

        if (!moduleTemplate.isModule()) {
            Files.copy(moduleTemplateFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        AbstractModuleTemplateProcessor processor;
        final Map<String, String> versionProps = fpArtifactVersions.get(pkg.getFeaturePackRuntime().getFPID().getProducer());
        final Path targetDir = runtime.getStagedDir().resolve(moduleXmlRelativePath.toString());
        if (thinServer) {
            processor = new ThinModuleTemplateProcessor(this,
                    artifactInstaller,
                    moduleXmlRelativePath,
                    moduleTemplate,
                    versionProps, channelArtifactResolution,
                    requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()));
        } else {
            processor = new FatModuleTemplateProcessor(this, artifactInstaller,
                    targetDir, moduleTemplate, versionProps,
                    channelArtifactResolution,requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()));
        }
        processor.process();
        moduleTemplate.store();
    }

    public void addExampleConfigs(FeaturePackRuntime fp, ExampleFpConfigs exampleConfigs) throws ProvisioningException {
        final FPID originFpId;
        if(exampleConfigs.getOrigin() != null) {
            originFpId = fp.getSpec().getFeaturePackDep(exampleConfigs.getOrigin()).getLocation().getFPID();
        } else {
            originFpId = fp.getFPID();
        }
        ExampleFpConfigs existingConfigs = this.exampleConfigs.get(originFpId);
        if(existingConfigs == null) {
            this.exampleConfigs = CollectionUtils.put(this.exampleConfigs, originFpId, exampleConfigs);
        } else {
            existingConfigs.addAll(exampleConfigs);
        }
    }

    private void extractSchemas(Path moduleArtifact) throws IOException {
        final Path targetSchemasDir = this.runtime.getStagedDir().resolve(WfConstants.DOCS).resolve(WfConstants.SCHEMA);
        Files.createDirectories(targetSchemasDir);
        try (FileSystem jarFS = FileSystems.newFileSystem(moduleArtifact, (ClassLoader) null)) {
            final Path schemaSrc = jarFS.getPath(WfConstants.SCHEMA);
            if (Files.exists(schemaSrc)) {
                ZipUtils.copyFromZip(schemaSrc.toAbsolutePath(), targetSchemasDir);
            }
        }
    }

    private boolean requireChannel(ProducerSpec spec) {
        WildFlyChannelResolutionMode mode = channelResolutionModes.get(spec);
        boolean requireChannel = false;
        if(mode != null) {
            requireChannel = WildFlyChannelResolutionMode.REQUIRED.equals(mode);
        }
        return requireChannel;
    }

    public void copyArtifact(CopyArtifact copyArtifact, PackageRuntime pkg) throws ProvisioningException {
        final MavenArtifact artifact = Utils.toArtifactCoords(copyArtifact.isFeaturePackVersion() ? fpArtifactVersions.get(pkg.getFeaturePackRuntime().getFPID().getProducer())
                        : mergedArtifactVersions,
                copyArtifact.getArtifact(), copyArtifact.isOptional(),
                channelArtifactResolution, requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()));
        if(artifact == null) {
            return;
        }
        try {
            log.verbose("Resolving artifact %s ", artifact);
            artifactResolver.resolve(artifact);
            if (channelArtifactResolution) {
                log.verbose("Resolved artifact %s ", artifact);
            }
            // If transformation occurs, the actual jar artifact file is renamed.
            // * Copied artifact for which we expect a well known name have a location file name, e.g.: jboss-modules.jar or bin/client/jboss-client.jar
            // * Copied artifact that are extracted, e.g.: openssl lib, the jar name is meaningless.
            // * Copied artifact that expect the name of the JAR artifact file to be used are impacted. (eg: resteasy-spring jar located in main/bundled/resteasy-spring-jar/resteasy-spring-XXX.Final-ee9.jar)
            Path jarSrc =  artifactInstaller.installCopiedArtifact(artifact);
            String location = copyArtifact.getToLocation();
            if (!location.isEmpty() && location.charAt(location.length() - 1) == '/') {
                // if the to location ends with a / then it is a directory
                // so we need to append the artifact name
                location += jarSrc.getFileName();
            }

            final Path jarTarget = runtime.getStagedDir().resolve(location);

            Files.createDirectories(jarTarget.getParent());

            log.verbose("Copying artifact %s to %s", jarSrc, jarTarget);
            if (copyArtifact.isExtract()) {
                Utils.extractArtifact(jarSrc, jarTarget, copyArtifact);
                if (artifactRecorder.isPresent()) {
                    artifactRecorder.get().cache(artifact, jarSrc);
                }
            } else {
                if (artifactRecorder.isPresent()) {
                    try {
                        artifactRecorder.get().record(artifact, jarTarget);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                IoUtils.copy(jarSrc, jarTarget);
            }
            if(schemaGroups.contains(artifact.getGroupId())) {
                extractSchemas(jarSrc);
            }
        } catch (IOException e) {
            throw new ProvisioningException("Failed to copy artifact " + artifact, e);
        }
    }

    void processSchemas(String groupId, Path artifactPath) throws IOException {
        if (schemaGroups.contains(groupId)) {
            extractSchemas(artifactPath);
        }
    }

    public void copyPath(final Path relativeTo, CopyPath copyPath) throws ProvisioningException {
        final Path src = relativeTo.resolve(copyPath.getSrc());
        if (!Files.exists(src)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(src));
        }
        final Path target = copyPath.getTarget() == null ? runtime.getStagedDir() : runtime.getStagedDir().resolve(copyPath.getTarget());
        if (copyPath.isReplaceProperties()) {
            if (!Files.exists(target.getParent())) {
                try {
                    Files.createDirectories(target.getParent());
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.mkdirs(target.getParent()), e);
                }
            }
            try {
                Files.walkFileTree(src, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                final Path targetDir = target.resolve(src.relativize(dir).toString());
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
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                PropertyReplacer.copy(file, target.resolve(src.relativize(file).toString()), mergedTaskPropsResolver,
                                        null);
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                throw new ProvisioningException(Errors.copyFile(src, target), e);
            }
        } else {
            try {
                IoUtils.copy(src, target);
            } catch (IOException e) {
                throw new ProvisioningException(Errors.copyFile(src, target));
            }
        }
    }

    public void deletePath(DeletePath deletePath) throws ProvisioningException {
        final Path path = runtime.getStagedDir().resolve(deletePath.getPath());
        if (!Files.exists(path)) {
            return;
        }
        if(deletePath.isRecursive()) {
            IoUtils.recursiveDelete(path);
            return;
        }
        if(deletePath.isIfEmpty()) {
            if(!Files.isDirectory(path)) {
                throw new ProvisioningException(Errors.notADir(path));
            }
            try(Stream<Path> stream = Files.list(path)) {
                if(stream.iterator().hasNext()) {
                    return;
                }
            } catch (IOException e) {
                throw new ProvisioningException(Errors.readDirectory(path));
            }
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.deletePath(path), e);
        }
    }

    private static void mkdirs(final WildFlyPackageTasks tasks, Path installDir) throws ProvisioningException {
        // make dirs
        for (String dirName : tasks.getMkDirs()) {
            try {
                Files.createDirectories(installDir.resolve(dirName));
            } catch (IOException e) {
                throw new ProvisioningException(Errors.mkdirs(installDir.resolve(dirName)));
            }
        }
    }

    void resolveMaven(MavenArtifact artifact) throws ProvisioningException {
        if (bulkResolveArtifacts && artifactCache.containsKey(artifact)) {
            final MavenArtifact resolvedArtifact = artifactCache.get(artifact);
            artifact.setVersion(resolvedArtifact.getVersion());
            artifact.setPath(resolvedArtifact.getPath());
        } else {
            maven.resolve(artifact);
        }
        // These properties are present in *-licenses.xml and must be replaced by the resolved ones.
        resolvedVersionsProperties.put("version."+artifact.getGroupId()+"."+artifact.getArtifactId(), artifact.getVersion());
    }

    boolean isOverriddenArtifact(MavenArtifact artifact) throws ProvisioningException {
        return Utils.containsArtifact(overriddenArtifactVersions, artifact);
    }

    private void invokeConfigGenerator(Class<?> configHandlerCls, Object generator, boolean forkEmbedded,
                                       String resetEmbeddedSystemProperties, String stabilityLevel)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        try {
            final Method m = configHandlerCls.getMethod(CONFIG_GEN_METHOD, ProvisioningRuntime.class, boolean.class, String.class, String.class);
            m.invoke(generator, runtime, forkEmbedded, resetEmbeddedSystemProperties, stabilityLevel);
        } catch (NoSuchMethodException e) {
            if (stabilityLevel != null && !stabilityLevel.isEmpty()) {
                throw e;
            }
            final Method m = configHandlerCls.getMethod(CONFIG_GEN_METHOD, ProvisioningRuntime.class, boolean.class, String.class);
            m.invoke(generator, runtime, forkEmbedded, resetEmbeddedSystemProperties);
        }
    }
}
