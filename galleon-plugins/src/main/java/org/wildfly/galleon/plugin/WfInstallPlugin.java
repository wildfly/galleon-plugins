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
import org.jboss.galleon.Constants;
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
import org.wildfly.galleon.plugin.config.AssembleShadedArtifact;
import org.wildfly.galleon.plugin.config.CopyArtifact;
import org.wildfly.galleon.plugin.config.CopyPath;
import org.wildfly.galleon.plugin.config.DeletePath;
import org.wildfly.galleon.plugin.config.ExampleFpConfigs;
import org.wildfly.galleon.plugin.config.LineEndingsTask;
import org.wildfly.galleon.plugin.config.XslTransform;
import org.wildfly.galleon.plugin.server.ForkedEmbeddedUtil;

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
    private SbomArtifactRecorder sbomGenerator;
    private String licenseMode;
    /** Whether a CycloneDX SBOM generation failure should abort provisioning; see {@link #OPTION_CYCLONEDX_FAIL_ON_ERROR_NAME}. */
    private boolean failOnSbomError;

    public interface ArtifactResolver {
        void resolve(MavenArtifact artifact) throws ProvisioningException;
    }

    public interface ArtifactGroupResolver {
        void resolve(Collection<MavenArtifact> artifacts) throws ProvisioningException;
    }

    private static final String CONFIG_GEN_METHOD = "generate";
    private static final String CONFIG_GEN_GA = "org.wildfly.galleon-plugins:wildfly-config-gen";
    private static final String GALLEON_PLUGINS_GA = "org.wildfly.galleon-plugins:wildfly-galleon-plugins";
    private static final String CONFIG_GEN_CLASS = "org.wildfly.galleon.plugin.config.generator.WfConfigGenerator";
    private static final String CLI_SCRIPT_RUNNER_CLASS = "org.wildfly.galleon.plugin.config.generator.CliScriptRunner";
    private static final String CLI_SCRIPT_RUNNER_METHOD = "runCliScript";
    private static final String JBOSS_MODULES_GA = "org.jboss.modules:jboss-modules";
    private static final String WILDFLY_CLI_GA = "org.wildfly.core:wildfly-cli";
    private static final String WILDFLY_LAUNCHER_GA = "org.wildfly.launcher:wildfly-launcher";

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
    /** License source: read {@code docs/licenses/*-licenses.xml} (the default). */
    private static final String LICENSE_MODE_XML = "licenses.xml";
    /** License source: resolve declared licenses from Maven POMs (remote). */
    private static final String LICENSE_MODE_POM = "pom";
    /** License collection disabled. */
    private static final String LICENSE_MODE_OFF = "off";
    private static final ProvisioningOption OPTION_CYCLONEDX = ProvisioningOption.builder("jboss-cyclonedx")
            .setBooleanValueSet()
            .setDefaultValue(Constants.TRUE)
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_FORMAT = ProvisioningOption.builder("jboss-cyclonedx-format")
            .setDefaultValue("json")
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_OUTPUT = ProvisioningOption.builder("jboss-cyclonedx-output")
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_ONLY = ProvisioningOption.builder("jboss-cyclonedx-only")
            .setBooleanValueSet()
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_LICENSES = ProvisioningOption.builder("jboss-cyclonedx-licenses")
            .setDefaultValue(LICENSE_MODE_OFF)
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_PRETTY_PRINT = ProvisioningOption.builder("jboss-cyclonedx-pretty-print")
            .setBooleanValueSet()
            .build();
    private static final ProvisioningOption OPTION_CYCLONEDX_SCHEMA_VERSION = ProvisioningOption.builder("jboss-cyclonedx-schema-version")
            .setDefaultValue("1.7")
            .build();
    /* Overrides the product CPE in the SBOM's main component for the exceptional case where the manifest-derived CPE is wrong or absent */
    private static final ProvisioningOption OPTION_CYCLONEDX_PRODUCT_CPE = ProvisioningOption.builder("jboss-cyclonedx-product-cpe")
            .build();
    /**
     * When {@code true}, a failure to generate the CycloneDX SBOM aborts provisioning.
     * When {@code false} (the default), such a failure is logged as a warning and
     * provisioning continues without a complete SBOM. This option has no effect in
     * SBOM-only mode, where the SBOM is the sole output and failures are always fatal.
     */
    static final String OPTION_CYCLONEDX_FAIL_ON_ERROR_NAME = "jboss-cyclonedx-fail-on-error";
    private static final ProvisioningOption OPTION_CYCLONEDX_FAIL_ON_ERROR = ProvisioningOption.builder(OPTION_CYCLONEDX_FAIL_ON_ERROR_NAME)
            .setBooleanValueSet()
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

    private Map<FPID, ExampleFpConfigs> exampleConfigs = new LinkedHashMap<>();

    private ProgressTracker<PackageRuntime> pkgProgressTracker;

    private MavenRepoManager maven;

    private Map<Path, PackageRuntime> jbossModules = new LinkedHashMap<>();

    private Path generatedMavenRepo;

    private AbstractArtifactInstaller artifactInstaller;
    private ArtifactResolver artifactResolver;
    private ArtifactGroupResolver artifactGroupResolver;
    private boolean channelArtifactResolution;

    private boolean bulkResolveArtifacts;

    private final Map<MavenArtifact, MavenArtifact> artifactCache = new HashMap<>();
    private final Map<Path, ModuleTemplate> moduleTemplateCache = new HashMap<>();

    private final Map<String, String> resolvedVersionsProperties = new HashMap<>();
    private Map<ProducerSpec, WildFlyChannelResolutionMode> channelResolutionModes = new LinkedHashMap<>();
    private Map<String, ProducerSpec> gaToProducer = new HashMap<>();
    private final Map<String, ShadedModel> shadedPackages = new HashMap<>();

    @Override
    protected List<ProvisioningOption> initPluginOptions() {
        return Arrays.asList(OPTION_MVN_DIST, OPTION_DUMP_CONFIG_SCRIPTS,
                             OPTION_FORK_EMBEDDED, OPTION_MVN_REPO,
                             OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES,
                             OPTION_OVERRIDDEN_ARTIFACTS, OPTION_BULK_RESOLVE_ARTIFACTS,
                             OPTION_RECORD_ARTIFACTS,
                             OPTION_CYCLONEDX, OPTION_CYCLONEDX_FORMAT,
                             OPTION_CYCLONEDX_OUTPUT, OPTION_CYCLONEDX_ONLY,
                             OPTION_CYCLONEDX_LICENSES, OPTION_CYCLONEDX_PRETTY_PRINT,
                             OPTION_CYCLONEDX_SCHEMA_VERSION, OPTION_CYCLONEDX_PRODUCT_CPE,
                             OPTION_CYCLONEDX_FAIL_ON_ERROR);
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
        final String value = runtime.getLowestConfigStability();
        return value == null ? "" : value;
    }

    private boolean getBooleanOption(ProvisioningOption option) throws ProvisioningException {
        final boolean set = runtime.isOptionSet(option);
        return booleanOptionValue(set, set ? runtime.getOptionValue(option) : null,
                option.getDefaultValue());
    }

    /**
     * Resolves the effective boolean value of a plugin option.
     *
     * <p>When the option is not explicitly set the declared {@code defaultValue}
     * applies (a {@code null} default means the option is off). When it is set,
     * a bare flag (a {@code null} value) counts as {@code true}; otherwise the
     * value is parsed as a boolean.</p>
     *
     * @param set          whether the option was explicitly set by the caller
     * @param value        the explicitly set value, or {@code null} for a bare flag
     * @param defaultValue the option's declared default, may be {@code null}
     * @return the effective boolean value of the option
     */
    static boolean booleanOptionValue(boolean set, String value, String defaultValue) {
        if (!set) {
            return Boolean.parseBoolean(defaultValue);
        }
        return value == null || Boolean.parseBoolean(value);
    }

    private Optional<ArtifactRecorder> initArtifactRecorder(ProvisioningRuntime runtime) throws ProvisioningException {
        final ArtifactRecorder txtRecorder = initTxtRecorder(runtime);
        sbomGenerator = initSbomRecorder(runtime);
        // Guard SBOM recording with the fail-on-error policy, leaving the
        // artifact-versions manifest (txtRecorder) fatal on failure.
        final ArtifactRecorder sbomRecorder = sbomGenerator == null ? null
                : new ErrorHandlingArtifactRecorder(sbomGenerator, failOnSbomError, log,
                        "WARNING: CycloneDX SBOM generation failed; provisioning continues without a complete SBOM. Set "
                        + OPTION_CYCLONEDX_FAIL_ON_ERROR_NAME + "=true to fail provisioning on SBOM errors instead.");

        if (txtRecorder != null && sbomRecorder != null) {
            return Optional.of(new ChainedArtifactRecorder(List.of(txtRecorder, sbomRecorder)));
        }
        if (txtRecorder != null) {
            return Optional.of(txtRecorder);
        }
        if (sbomRecorder != null) {
            return Optional.of(sbomRecorder);
        }
        return Optional.empty();
    }

    private ArtifactRecorder initTxtRecorder(ProvisioningRuntime runtime) throws ProvisioningException {
        if (!runtime.isOptionSet(OPTION_RECORD_ARTIFACTS)) {
            return null;
        }
        final String pathValue = runtime.getOptionValue(OPTION_RECORD_ARTIFACTS);
        if (pathValue == null || pathValue.isEmpty()) {
            return null;
        }
        try {
            log.verbose("Starting artifact log");
            return new ArtifactsTxtRecorder(runtime.getStagedDir(), Path.of(pathValue));
        } catch (IOException e) {
            throw new ProvisioningException("Unable to create artifact.log", e);
        }
    }

    private SbomArtifactRecorder initSbomRecorder(ProvisioningRuntime runtime) throws ProvisioningException {
        if (!getBooleanOption(OPTION_CYCLONEDX) && !getBooleanOption(OPTION_CYCLONEDX_ONLY)) {
            return null;
        }
        final String format = resolveFormat(runtime);
        final Path outputPath = resolveOutputPath(runtime, format);
        licenseMode = resolveLicenseMode(runtime);
        final boolean prettyPrint = getBooleanOption(OPTION_CYCLONEDX_PRETTY_PRINT);
        final String schemaVersion = runtime.getOptionValue(OPTION_CYCLONEDX_SCHEMA_VERSION);
        failOnSbomError = getBooleanOption(OPTION_CYCLONEDX_FAIL_ON_ERROR);
        log.verbose("CycloneDX SBOM generation enabled, format=%s, output=%s, licenses=%s, prettyPrint=%s, schemaVersion=%s, failOnError=%s",
                format, outputPath, licenseMode, prettyPrint, schemaVersion != null ? schemaVersion : "default", failOnSbomError);
        final SbomArtifactRecorder recorder = new SbomArtifactRecorder(runtime.getStagedDir(), outputPath, format, prettyPrint);
        try {
            recorder.setSchemaVersion(schemaVersion);
        } catch (IllegalArgumentException e) {
            throw new ProvisioningException("Unsupported value for jboss-cyclonedx-schema-version: " + e.getMessage(), e);
        }
        recorder.setProductCpe(runtime.getOptionValue(OPTION_CYCLONEDX_PRODUCT_CPE));
        return recorder;
    }

    private String resolveLicenseMode(ProvisioningRuntime runtime) throws ProvisioningException {
        String value = runtime.getOptionValue(OPTION_CYCLONEDX_LICENSES);
        if (value == null || value.isEmpty()) {
            return LICENSE_MODE_OFF;
        }
        value = value.trim();
        if (value.equalsIgnoreCase(LICENSE_MODE_XML) || value.equalsIgnoreCase("true")) {
            return LICENSE_MODE_XML;
        }
        if (value.equalsIgnoreCase(LICENSE_MODE_POM)) {
            return LICENSE_MODE_POM;
        }
        if (value.equalsIgnoreCase(LICENSE_MODE_OFF) || value.equalsIgnoreCase("none")
                || value.equalsIgnoreCase("false")) {
            return LICENSE_MODE_OFF;
        }
        throw new ProvisioningException("Unsupported value for jboss-cyclonedx-licenses: '" + value
                + "'. Expected one of: " + LICENSE_MODE_XML + ", " + LICENSE_MODE_POM + ", " + LICENSE_MODE_OFF + ".");
    }

    /**
     * Builds and installs the license source on the SBOM recorder according to
     * the resolved {@link #licenseMode}. Called just before the SBOM is written,
     * once feature-pack content (from which {@code licenses.xml} is read) is
     * available. A no-op when SBOM generation or license collection is off.
     */
    private void configureLicenseSource(ProvisioningRuntime runtime) throws ProvisioningException {
        if (sbomGenerator == null || licenseMode == null || LICENSE_MODE_OFF.equals(licenseMode)) {
            return;
        }
        if (LICENSE_MODE_POM.equals(licenseMode)) {
            final MavenRepoManager mavenResolver =
                    (MavenRepoManager) runtime.getArtifactResolver(MavenRepoManager.REPOSITORY_ID);
            sbomGenerator.setLicenseSource(new PomLicenseResolver(mavenResolver));
        } else {
            final List<Path> summaries = collectLicenseSummaries(runtime);
            log.verbose("CycloneDX: using %d licenses.xml summary file(s)", summaries.size());
            sbomGenerator.setLicenseSource(LicensesXmlSource.from(summaries));
        }
    }

    /**
     * Collects the {@code docs/licenses/*-licenses.xml} summary files from all
     * feature-pack package content. Reading from package content (rather than
     * the staged distribution) means this works in SBOM-only mode too, where no
     * files are installed.
     */
    private List<Path> collectLicenseSummaries(ProvisioningRuntime runtime) throws ProvisioningException {
        final List<Path> result = new ArrayList<>();
        for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            for (PackageRuntime pkg : fp.getPackages()) {
                final Path contentDir = pkg.getContentDir();
                if (contentDir == null) {
                    continue;
                }
                final Path licensesDir = contentDir.resolve("docs").resolve("licenses");
                if (!Files.isDirectory(licensesDir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(licensesDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith("licenses.xml"))
                            .forEach(result::add);
                } catch (IOException e) {
                    throw new ProvisioningException("Failed to list " + licensesDir, e);
                }
            }
        }
        return result;
    }

    private String resolveFormat(ProvisioningRuntime runtime) throws ProvisioningException {
        if (runtime.isOptionSet(OPTION_CYCLONEDX_FORMAT)) {
            final String value = runtime.getOptionValue(OPTION_CYCLONEDX_FORMAT);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "json";
    }

    private Path resolveOutputPath(ProvisioningRuntime runtime, String format) throws ProvisioningException {
        if (runtime.isOptionSet(OPTION_CYCLONEDX_OUTPUT)) {
            final String value = runtime.getOptionValue(OPTION_CYCLONEDX_OUTPUT);
            if (value != null && !value.isEmpty()) {
                Path targetFile = runtime.getStagedDir().resolve(value).normalize();
                if (!targetFile.
                        startsWith(runtime.getStagedDir().normalize() + File.separator)) {
                    throw new ProvisioningException("sbom file path " + value
                            + " must be relative to the server installation directory");
                }
                return targetFile;
            }
        }
        final String fileName = "json".equalsIgnoreCase(format) ? "sbom.cdx.json" : "sbom.cdx.xml";
        return runtime.getStagedDir().resolve(fileName);
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
        artifactRecorder = initArtifactRecorder(runtime);

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
        // SBOM-only mode also relies on the resolver to obtain channel-resolved versions.
        artifactResolver = this::resolveMaven;
        artifactGroupResolver = this::resolveMaven;
        artifactInstaller = new SimpleArtifactInstaller(artifactResolver, generatedMavenRepo, artifactRecorder);

        if (getBooleanOption(OPTION_CYCLONEDX_ONLY)) {
            generateSbomOnly(runtime);
            return;
        }

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
        // Must first retrieve the shaded that could be required by other packages
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            processShaded(fp);
        }
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
                            String stabilityLevel = getStabilityLevel();
                            byte[] content;
                            if (stabilityLevel != null && !stabilityLevel.isEmpty()) {
                                List<String> lines = Files.readAllLines(finalizeCli);
                                StringBuilder builder = new StringBuilder();
                                // Do we have an embed-server command?
                                for (String l : lines) {
                                    String trimLine = l.trim();
                                    if (trimLine.startsWith("embed-server")) {
                                        if (!trimLine.contains("--stability=")) {
                                            l += " --stability=" + stabilityLevel;
                                        }
                                    }
                                    builder.append(l).append(System.lineSeparator());
                                }
                                content = builder.toString().getBytes();
                            } else {
                                content = Files.readAllBytes(finalizeCli);
                            }
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
                        Path props = ForkedEmbeddedUtil.storeSystemProps();
                        props.toFile().deleteOnExit();
                        try {
                            final Class<?> cliScriptRunnerCls = cliScriptCl.loadClass(CLI_SCRIPT_RUNNER_CLASS);
                            final Method m = cliScriptRunnerCls.getMethod(CLI_SCRIPT_RUNNER_METHOD, Path.class, Path.class, Path.class, MessageWriter.class);
                            m.invoke(null, runtime.getStagedDir(), script, props, log);
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
                configureLicenseSource(runtime);
                artifactRecorder.get().writeManifest();
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

        // In this classloader we need CLI + config gen
        URL[] cp;
        List<URL> urls = new ArrayList<>();
        // In this classloader we need CLI + JBoss Modules
        URL[] cpEmbedded;
        List<URL> urlsEmbedded = new ArrayList<>();
        List<URL> cliDependencies = new ArrayList<>();
        try {
            MavenArtifact artifact = Utils.toArtifactCoords(mergedArtifactVersions, CONFIG_GEN_GA,
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(CONFIG_GEN_GA)));
            artifactResolver.resolve(artifact);
            if (artifactRecorder.isPresent()) {
                artifactRecorder.get().cache(artifact, artifact.getPath());
            }
            urls.add(artifact.getPath().toUri().toURL());
            ShadedModel model = shadedPackages.get("org.wildfly.core.wildfly-cli.shaded");
            if (model == null) {
                // This can occur in tests that rely on WildFly version that doesn't contain shaded models.
                log.print("WARNING: defaulting to wildfly-cli:client shaded jar.");
                artifact = Utils.toArtifactCoords(mergedArtifactVersions, WILDFLY_CLI_GA+"::client",
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(WILDFLY_CLI_GA)));
                artifactResolver.resolve(artifact);
                urls.add(artifact.getPath().toUri().toURL());
                urlsEmbedded.add(artifact.getPath().toUri().toURL());
            } else {
                for (MavenArtifact a : model.getArtifacts()) {
                    cliDependencies.add(a.getPath().toUri().toURL());
                }
                urls.addAll(cliDependencies);
                urlsEmbedded.addAll(cliDependencies);
            }
            cp = new URL[urls.size()];
            cp = urls.toArray(cp);

            artifact = Utils.toArtifactCoords(mergedArtifactVersions, JBOSS_MODULES_GA,
                    false, channelArtifactResolution, requireChannel(gaToProducer.get(JBOSS_MODULES_GA)));
            artifactResolver.resolve(artifact);
            urlsEmbedded.add(artifact.getPath().toUri().toURL());
            cpEmbedded = new URL[urlsEmbedded.size()];
            cpEmbedded = urlsEmbedded.toArray(cpEmbedded);

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
        // We need to delegate to resolve Galleon classes.
        final URLClassLoader configGenCl = new URLClassLoader(cp, originalCl);
        // Embedded is loaded from an isolated classloader.
        final URLClassLoader embeddedCl = new URLClassLoader(cpEmbedded, null);
        Thread.currentThread().setContextClassLoader(configGenCl);
        try {
            final Class<?> configHandlerCls = configGenCl.loadClass(CONFIG_GEN_CLASS);
            // Embedded is loaded from an isolated classloader with no delegation
            Method initEmbedded = configHandlerCls.getMethod("initializeEmbedded", ClassLoader.class);
            initEmbedded.invoke(null, embeddedCl);
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
            try {
                embeddedCl.close();
            } catch (IOException e) {
            }
        }
    }

    private void processShaded(final FeaturePackRuntime fp) throws ProvisioningException {
        for(PackageRuntime pkg : fp.getPackages()) {
            final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
            if(!Files.exists(pmWfDir)) {
                continue;
            }
            final Path shadedDir = pmWfDir.resolve(WfConstants.SHADED);
            if(Files.exists(shadedDir)) {
                try {
                    shadedPackages.put(pkg.getName(), new ShadedModel(
                            requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()),
                            shadedDir.resolve(ShadedModel.FILE_NAME),
                            runtime.getTmpPath(),
                            artifactGroupResolver, log, mergedArtifactVersions, artifactInstaller, channelArtifactResolution, artifactRecorder));
                } catch (IOException ex) {
                    throw new ProvisioningException(ex);
                }
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
            this.exampleConfigs = CollectionUtils.putLinked(this.exampleConfigs, originFpId, exampleConfigs);
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

    private boolean requireChannel(String artifactGA) {
        return requireChannel(gaToProducer.get(artifactGA));
    }

    boolean requireChannel(ProducerSpec spec) {
        WildFlyChannelResolutionMode mode = channelResolutionModes.get(spec);
        boolean requireChannel = false;
        if(mode != null) {
            requireChannel = WildFlyChannelResolutionMode.REQUIRED.equals(mode);
        }
        return requireChannel;
    }

    public void assembleArtifact(AssembleShadedArtifact copyArtifact, PackageRuntime pkg) throws ProvisioningException {
        try {
            ShadedModel model = shadedPackages.get(copyArtifact.getShadedModelPackage());
            String location = copyArtifact.getToLocation();
            final Path jarTarget = runtime.getStagedDir().resolve(location);
            Files.createDirectories(jarTarget.getParent());
            model.buildJar(jarTarget);
            recordShadedComponent(location, jarTarget, model, pkg);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to copy shaded jar " + copyArtifact.getShadedModelPackage(), e);
        }
    }

    private void recordShadedComponent(String toLocation, Path jarTarget, ShadedModel model, PackageRuntime pkg) {
        if (sbomGenerator == null) {
            return;
        }
        final String version = pkg.getFeaturePackRuntime().getFPID().getBuild();
        final List<MavenArtifact> dependencies = getShadedDependencyCoords(model, pkg);
        sbomGenerator.recordShadedComponent(toLocation, version, jarTarget, dependencies);
    }

    private List<MavenArtifact> getShadedDependencyCoords(ShadedModel model, PackageRuntime pkg) {
        try {
            return model.getArtifacts();
        } catch (IOException | ProvisioningException e) {
            log.verbose("Failed to extract shaded dependency coordinates: %s", e.getMessage());
            return List.of();
        }
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
                    try {
                        artifactRecorder.get().record(artifact, jarTarget);
                    } catch (IOException e) {
                        throw new ProvisioningException("Unable to record extracted artifact", e);
                    }
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
            // only attempt to extract schemas if the artifact is a zip archive
            if(schemaGroups.contains(artifact.getGroupId())
                    && (artifact.getExtension().equals("jar") || artifact.getExtension().equals("zip"))) {
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

    void resolveMaven(Collection<MavenArtifact> artifacts) throws ProvisioningException {
        for (MavenArtifact artifact : artifacts) {
            resolveMaven(artifact);
        }
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

    /**
     * Generates a CycloneDX SBOM without provisioning the server.
     *
     * <p>Walks all included packages to discover artifact references from
     * module templates, CopyArtifact tasks, and shaded model dependencies.
     * Records each discovered artifact to the SBOM generator with no target
     * path (no evidence/occurrences). Writes the SBOM and returns.</p>
     *
     * @param runtime the provisioning runtime
     * @throws ProvisioningException if an error occurs during SBOM generation
     */
    private void generateSbomOnly(ProvisioningRuntime runtime) throws ProvisioningException {
        log.verbose("CycloneDX SBOM-only mode: generating SBOM without provisioning");
        if (sbomGenerator == null) {
            throw new ProvisioningException("jboss-cyclonedx-only requires SBOM generator to be initialized");
        }
        try {
            final Map<String, ShadedModelInfo> shadedModels = collectShadedModelInfo(runtime);
            for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
                for (PackageRuntime pkg : fp.getPackages()) {
                    collectModuleArtifacts(pkg, sbomGenerator);
                    collectTaskArtifacts(pkg, sbomGenerator, shadedModels);
                }
            }
            // config-gen is resolved directly by the plugin in generateConfigs()
            // rather than through module.xml or tasks.xml, so it must be recorded
            // explicitly here since generateConfigs() is skipped in SBOM-only mode.
            recordProvisioningToolArtifact(CONFIG_GEN_GA);
            // Remove content provisioned by Galleon core so only the SBOM ends up in the target.
            clearProvisionedContent(runtime);
            configureLicenseSource(runtime);
            sbomGenerator.writeManifest();
        } catch (IOException e) {
            // SBOM-only mode produces nothing but the SBOM, so a failure is always fatal;
            // jboss-cyclonedx-fail-on-error does not apply here.
            throw new ProvisioningException("Failed to generate the CycloneDX SBOM", e);
        }
    }

    /**
     * Removes the content provisioned by Galleon core so that only the SBOM
     * remains in the target, leaving an empty {@code standalone} directory.
     * Used by SBOM-only mode, which does not install a server.
     */
    private void clearProvisionedContent(ProvisioningRuntime runtime) throws ProvisioningException {
        final Path stagedDir = runtime.getStagedDir();
        try {
            if (Files.exists(stagedDir)) {
                try (Stream<Path> entries = Files.list(stagedDir)) {
                    entries.forEach(IoUtils::recursiveDelete);
                }
                Files.createDirectories(stagedDir.resolve("standalone"));
            }
        } catch (IOException e) {
            throw new ProvisioningException("Failed to clear provisioned content for SBOM-only mode", e);
        }
    }

    /**
     * Records a provisioning tool artifact. These artifacts (such as
     * config-gen) are resolved directly by the plugin rather than
     * through module.xml or tasks.xml, so they must be recorded explicitly.
     */
    private void recordProvisioningToolArtifact(String ga) throws IOException, ProvisioningException {
        if (!mergedArtifactVersions.containsKey(ga)) {
            return;
        }
        final MavenArtifact artifact = resolveEffectiveVersion(Utils.toArtifactCoords(mergedArtifactVersions, ga,
                false, channelArtifactResolution, requireChannel(gaToProducer.get(ga))));
        sbomGenerator.recordToolDependency(artifact);
    }

    /**
     * Resolves the artifact's version from the channel, when channel-based
     * resolution is enabled, using the plugin's configured resolver.
     *
     * @see #resolveEffectiveVersion(MavenArtifact, boolean, ArtifactResolver)
     */
    private MavenArtifact resolveEffectiveVersion(MavenArtifact artifact) throws ProvisioningException {
        return resolveEffectiveVersion(artifact, channelArtifactResolution, artifactResolver);
    }

    /**
     * Resolves an artifact's effective version from the channel when
     * channel-based resolution is enabled, so that SBOM components carry
     * channel-accurate versions.
     *
     * <p>In non-channel mode the version already comes from the feature-pack
     * version properties, so the artifact is returned untouched. When a channel
     * is in use the declared version may be absent (the channel is the source of
     * truth) or overridden by the channel, so it must be resolved; that
     * resolution only determines the version (and caches the artifact) and does
     * not provision anything into the server.</p>
     *
     * @param artifact                  the artifact to resolve, may be {@code null}
     * @param channelArtifactResolution whether channel-based resolution is enabled
     * @param resolver                  the resolver used to obtain the channel version
     * @return {@code artifact} (channel-resolved when applicable), or {@code null}
     *         when {@code artifact} is {@code null}
     */
    static MavenArtifact resolveEffectiveVersion(MavenArtifact artifact, boolean channelArtifactResolution,
            ArtifactResolver resolver) throws ProvisioningException {
        if (artifact != null && channelArtifactResolution) {
            resolver.resolve(artifact);
        }
        return artifact;
    }

    /**
     * Collects shaded model info (name + dependency coordinates) for all packages,
     * indexed by package name.
     */
    private Map<String, ShadedModelInfo> collectShadedModelInfo(ProvisioningRuntime runtime)
            throws ProvisioningException, IOException {
        final Map<String, ShadedModelInfo> result = new HashMap<>();
        for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            for (PackageRuntime pkg : fp.getPackages()) {
                final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
                if (!Files.exists(pmWfDir)) {
                    continue;
                }
                final Path shadedDir = pmWfDir.resolve(WfConstants.SHADED);
                if (!Files.exists(shadedDir)) {
                    continue;
                }
                final Path shadedModelFile = shadedDir.resolve(ShadedModel.FILE_NAME);
                if (!Files.exists(shadedModelFile)) {
                    continue;
                }
                final ShadedModel model = new ShadedModel(
                        requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()),
                        shadedModelFile, runtime.getTmpPath(), artifactGroupResolver, log,
                        mergedArtifactVersions, artifactInstaller, channelArtifactResolution, artifactRecorder);
                final String version = fp.getFPID().getBuild();
                // SBOM-only mode does not produce artifacts, so parse coordinates without
                // resolving. When a channel is active the version must be resolved from the
                // channel to be accurate; that resolution does not install the shaded jar.
                final List<MavenArtifact> deps = channelArtifactResolution
                        ? model.resolveDependencyCoords()
                        : model.parseDependencyCoords();
                result.put(pkg.getName(), new ShadedModelInfo(version, deps));
            }
        }
        return result;
    }

    /**
     * Holds pre-parsed shaded model metadata for SBOM-only mode.
     */
    private static class ShadedModelInfo {
        final String version;
        final List<MavenArtifact> dependencies;

        ShadedModelInfo(String version, List<MavenArtifact> dependencies) {
            this.version = version;
            this.dependencies = dependencies;
        }
    }

    /**
     * Walks module templates in a package to discover artifact references.
     *
     * <p>Finds all {@code module.xml} files in the package's module content
     * directory, parses {@code <artifact name="...">} elements, resolves
     * coordinates against the feature pack's artifact versions, and records
     * each artifact to the recorder with a null target path.</p>
     *
     * @param pkg      the package to scan for module templates
     * @param recorder the artifact recorder to record discovered artifacts
     * @throws IOException           if an I/O error occurs reading module templates
     * @throws ProvisioningException if artifact coordinate resolution fails
     */
    private void collectModuleArtifacts(PackageRuntime pkg, ArtifactRecorder recorder)
            throws IOException, ProvisioningException {
        final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
        if (!Files.exists(pmWfDir)) {
            return;
        }
        final Path moduleDir = pmWfDir.resolve(WfConstants.MODULE);
        if (!Files.exists(moduleDir)) {
            return;
        }
        final Map<String, String> versionProps = fpArtifactVersions.get(
                pkg.getFeaturePackRuntime().getFPID().getProducer());
        if (versionProps == null) {
            return;
        }
        Files.walkFileTree(moduleDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals(WfConstants.MODULE_XML)) {
                    collectArtifactsFromModuleXml(file, pkg, versionProps, recorder);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final String RESOURCES_CLASSIFIER = "resources";

    /**
     * Parses a single module.xml template and records its artifact references.
     *
     * <p>Artifacts with a {@code resources} classifier are recorded via
     * {@link ArtifactRecorder#recordResourceJar}.</p>
     */
    private void collectArtifactsFromModuleXml(Path moduleXml, PackageRuntime pkg,
            Map<String, String> versionProps, ArtifactRecorder recorder) throws IOException {
        try {
            final ModuleTemplate template = new ModuleTemplate(pkg, moduleXml, moduleXml);
            if (!template.isModule()) {
                return;
            }
            final Elements artifacts = template.getArtifacts();
            if (artifacts == null) {
                return;
            }
            for (int i = 0; i < artifacts.size(); i++) {
                final AbstractModuleTemplateProcessor.ModuleArtifact moduleArtifact =
                        new AbstractModuleTemplateProcessor.ModuleArtifact(
                                template, artifacts.get(i), versionProps, log, null,
                                channelArtifactResolution,
                                requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer()));
                final MavenArtifact artifact = moduleArtifact.getUnresolvedArtifact();
                if (artifact != null) {
                    if (isResourceJar(artifact) && sbomGenerator != null) {
                        // Resource JARs are resolved (and downloaded) by recordResolvedResourceJar
                        // so their bundled resources can be inspected.
                        recordResolvedResourceJar(artifact);
                    } else {
                        recorder.record(resolveEffectiveVersion(artifact), null);
                    }
                }
            }
        } catch (ProvisioningException e) {
            throw new IOException("Failed to collect artifacts from module template " + moduleXml, e);
        }
    }

    /**
     * Returns {@code true} if the artifact has a {@code resources} classifier,
     * indicating it may contain bundled web resources such as JavaScript libraries.
     */
    private static boolean isResourceJar(MavenArtifact artifact) {
        return RESOURCES_CLASSIFIER.equals(artifact.getClassifier());
    }

    /**
     * Resolves a resource-classifier artifact and records it with the SBOM generator
     * so that its bundled JavaScript libraries can be detected from source maps.
     */
    private void recordResolvedResourceJar(MavenArtifact artifact) throws IOException {
        try {
            maven.resolve(artifact);
            sbomGenerator.recordResourceJar(artifact, null, artifact.getPath());
        } catch (MavenUniverseException e) {
            log.verbose("Failed to resolve resource JAR %s, recording without JS detection: %s",
                    artifact, e.getMessage());
            sbomGenerator.record(artifact, null);
        }
    }

    /**
     * Parses tasks.xml in a package to discover artifact references.
     *
     * <p>Handles both {@link CopyArtifact} tasks (recorded as flat components)
     * and {@link AssembleShadedArtifact} tasks (recorded as shaded components
     * with nested dependencies, using pre-collected shaded model info).</p>
     *
     * @param pkg           the package to scan for task artifact references
     * @param recorder      the artifact recorder for CopyArtifact references
     * @param sbomGenerator the SBOM generator for shaded components, may be null
     * @param shadedModels  pre-collected shaded model info indexed by package name
     * @throws IOException           if an I/O error occurs reading tasks.xml
     * @throws ProvisioningException if task parsing or coordinate resolution fails
     */
    private void collectTaskArtifacts(PackageRuntime pkg, ArtifactRecorder recorder,
            Map<String, ShadedModelInfo> shadedModels)
            throws IOException, ProvisioningException {
        final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
        if (!Files.exists(pmWfDir)) {
            return;
        }
        final Path tasksXml = pmWfDir.resolve(WfConstants.TASKS_XML);
        if (!Files.exists(tasksXml)) {
            return;
        }
        final WildFlyPackageTasks pkgTasks = WildFlyPackageTasks.load(tasksXml);
        if (!pkgTasks.hasTasks()) {
            return;
        }
        for (WildFlyPackageTask task : pkgTasks.getTasks()) {
            if (task instanceof CopyArtifact) {
                collectCopyArtifact((CopyArtifact) task, pkg, recorder);
            } else if (task instanceof AssembleShadedArtifact && sbomGenerator != null) {
                collectAssembleShadedArtifact((AssembleShadedArtifact) task, shadedModels);
            }
        }
    }

    private void collectCopyArtifact(CopyArtifact copyTask, PackageRuntime pkg,
            ArtifactRecorder recorder) throws IOException, ProvisioningException {
        final Map<String, String> versionProps = copyTask.isFeaturePackVersion()
                ? fpArtifactVersions.get(pkg.getFeaturePackRuntime().getFPID().getProducer())
                : mergedArtifactVersions;
        final MavenArtifact artifact = resolveEffectiveVersion(Utils.toArtifactCoords(
                versionProps, copyTask.getArtifact(),
                copyTask.isOptional(), channelArtifactResolution,
                requireChannel(pkg.getFeaturePackRuntime().getFPID().getProducer())));
        if (artifact != null) {
            recorder.record(artifact, null);
        }
    }

    private void collectAssembleShadedArtifact(AssembleShadedArtifact task,
            Map<String, ShadedModelInfo> shadedModels) {
        final ShadedModelInfo info = shadedModels.get(task.getShadedModelPackage());
        if (info == null) {
            return;
        }
        sbomGenerator.recordShadedComponent(task.getToLocation(), info.version, null, info.dependencies);
    }
}
