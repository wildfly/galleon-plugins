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

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.PackageDependencySpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.PathFilter;
import org.jboss.galleon.xml.PackageXmlParser;
import org.wildfly.galleon.plugin.WfConstants;

/**
 * This Maven mojo creates a WildFly style feature-pack archive from the provided resources according to the
 * feature-pack build configuration file and attaches it to the current Maven project as an artifact.
 *
 * The content of the future feature-pack archive is first created in the directory called `layout` under the module's
 * build directory which is then ZIPped to create the feature-pack artifact.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "build-feature-pack", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WfFeaturePackBuildMojo extends AbstractFeaturePackBuildMojo {

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
        }
    };

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
     * Represents the directory containing child directories {@code packages}, {@code feature_groups}, {@code modules}
     * etc. Either an absolute path or a path relative to {@link #configDir}.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;


    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    /**
     * The artifactId for the generated feature-pack.
     */
    @Parameter(alias="feature-pack-artifact-id", defaultValue = "${project.artifactId}", required=false)
    private String fpArtifactId;

    /**
     * Used only for feature spec generation and indicates whether to launch
     * the embedded server to read feature descriptions in a separate process
     */
    @Parameter(alias = "fork-embedded", required = false)
    protected boolean forkEmbedded;

    /**
     * Used only for feature spec generation and points to a directory from
     * which the embedded WildFly instance will be started that is used for
     * exporting the meta-model. Intended mainly for debugging.
     */
    @Parameter(alias = "wildfly-home", property = "wfgp.wildflyHome", defaultValue = "${project.build.directory}/wildfly", required = true)
    protected File wildflyHome;

    /**
     * Used only for feature spec generation and points to a directory where
     * the module templates from the dependent feature packs are gathered before
     * they are transformed and copied under their default destination
     * {@link #wildflyHome}/modules. Intended mainly for debugging.
     */
    @Parameter(alias = "module-templates", property = "wfgp.moduleTemplatesDir", defaultValue = "${project.build.directory}/module-templates", required = true)
    protected File moduleTemplatesDir;

    /**
     * The directory where the generated feature specs are written.
     */
    @Parameter(alias = "feature-specs-output", defaultValue = "${project.build.directory}/resources/features", required = true)
    protected File featureSpecsOutput;

    private WildFlyFeaturePackBuild buildConfig;
    private Map<String, PackageSpec.Builder> extendedPackages = Collections.emptyMap();

    protected WildFlyFeaturePackBuild getBuildConfig() throws MojoExecutionException {
        return buildConfig == null ? buildConfig = Util.loadFeaturePackBuildConfig(configDir, configFile) : buildConfig;
    }

    @Override
    protected String getPackaging() {
        return "galleon-feature-pack";
    }

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        final Path targetResources = Paths.get(buildName, Constants.RESOURCES);
        final Path specsDir = configDir.getAbsoluteFile().toPath().resolve(resourcesDir);
        if (Files.exists(specsDir)) {
            try {
                IoUtils.copy(specsDir, targetResources);
            } catch (IOException e1) {
                throw new MojoExecutionException(Errors.copyFile(specsDir, targetResources), e1);
            }
        }

        setupDirs(buildName, fpArtifactId, WfConstants.LAYOUT, targetResources);
        final Path fpPackagesDir = getPackagesDir();

        // feature-pack build config
        buildConfig = getBuildConfig();
        setStability(buildConfig);
        if(buildConfig.hasStandaloneExtensions() || buildConfig.hasDomainExtensions() || buildConfig.hasHostExtensions()) {
            try {
                new FeatureSpecGeneratorInvoker(this).execute();
                // Move the generated management-api.json outside
                Path p = featureSpecsOutput.toPath().resolve("management-api.json");
                Files.move(p, Paths.get(buildName).resolve("management-api.json"));
            } catch (IOException ex) {
                throw new MojoExecutionException(ex);
            }
        }

        FeaturePackLocation fpl = buildConfig.getProducer();
        if (!fpl.hasUniverse() && !fpl.hasBuild()) {
            fpl = FeaturePackLocation.fromString(fpl.toString() + ":" + project.getVersion());
        } else {
            String channel = fpl.getChannelName();
            if (channel == null || channel.isEmpty()) {
                final String v = project.getVersion();
                final int i = v.indexOf('.');
                channel = i < 0 ? v : v.substring(0, i);
            }
            fpl = new FeaturePackLocation(fpl.getUniverse(), fpl.getProducerName(), channel, null, project.getVersion());
        }

        // feature-pack builder
        final FeaturePackDescription.Builder fpBuilder = FeaturePackDescription.builder(FeaturePackSpec.builder(fpl.getFPID()));

        for (String path: buildConfig.getSystemPaths()) {
            fpBuilder.getSpecBuilder().addSystemPaths(path);
        }

        for(String defaultPackage : buildConfig.getDefaultPackages()) {
            fpBuilder.getSpecBuilder().addDefaultPackage(defaultPackage);
        }

        try {
            processFeaturePackDependencies(buildConfig, fpBuilder.getSpecBuilder());
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to process dependencies", e);
        }

        final Path srcModulesDir = targetResources.resolve(WfConstants.MODULES);
        if (Files.exists(srcModulesDir)) {
            addModulePackages(srcModulesDir, fpBuilder, targetResources);
        } else{
            getLog().warn("No modules found at " + srcModulesDir);
        }

        final Path contentDir = targetResources.resolve(Constants.CONTENT);
        if (Files.exists(contentDir)) {
            try {
                packageContent(fpBuilder, contentDir, fpPackagesDir, targetResources);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process content", e);
            }
        }

        if(buildConfig.hasSchemaGroups()) {
            addDocsSchemas(fpPackagesDir, fpBuilder, targetResources);
        }

        final PackageSpec.Builder docsBuilder = getExtendedPackage(WfConstants.DOCS, false, targetResources);
        if(docsBuilder != null) {
            fpBuilder.getSpecBuilder().addDefaultPackage(addPackage(fpPackagesDir, fpBuilder, docsBuilder).getName());
//            if(fpBuilder.hasPackage("docs.examples.configs")) {
//                getExtendedPackage("docs.examples", true).addPackageDep("docs.examples.configs", true);
//            }
        }

        if (!getFpDependencies().isEmpty() && !extendedPackages.isEmpty()) {
            for (Map.Entry<String, FeaturePackDescription> fpDep : getFpDependencies().entrySet()) {
                final FeaturePackDescription fpDepLayout = fpDep.getValue();
                for(Map.Entry<String, PackageSpec.Builder> entry : extendedPackages.entrySet()) {
                    if(fpDepLayout.hasPackage(entry.getKey())) {
                        entry.getValue().addPackageDep(fpDep.getKey(), entry.getKey(), true);
                    }
                }
            }
        }

        for(Map.Entry<String, PackageSpec.Builder> entry : extendedPackages.entrySet()) {
            addPackage(fpPackagesDir, fpBuilder, entry.getValue());
        }

        buildFeaturePack(fpBuilder, buildConfig);
    }

    private PackageSpec.Builder getExtendedPackage(String name, boolean create, Path resourcesDir) throws MojoExecutionException {
        PackageSpec.Builder pkgBuilder = extendedPackages.get(name);
        if(pkgBuilder == null) {
            if(!create) {
                return null;
            }
            pkgBuilder = PackageSpec.builder(name);
            Path existingPackage = resourcesDir.resolve(Constants.PACKAGES).resolve(name).resolve("package.xml");
            if (Files.exists(existingPackage)) {
                try {
                    try (FileReader stream = new FileReader(existingPackage.toFile())) {
                        PackageSpec spec = PackageXmlParser.getInstance().parse(stream);
                        for (PackageDependencySpec dep : spec.getLocalPackageDeps()) {
                            pkgBuilder.addPackageDep(dep);
                        }
                        for (String origin : spec.getPackageOrigins()) {
                            for (PackageDependencySpec dep : spec.getExternalPackageDeps(origin)) {
                                pkgBuilder.addPackageDep(origin, dep);
                            }
                        }
                    }
                    Files.delete(existingPackage);
                } catch (IOException | XMLStreamException ex) {
                    throw new MojoExecutionException(ex);
                }
            }
            extendedPackages = CollectionUtils.put(extendedPackages, name, pkgBuilder);
        }
        return pkgBuilder;
    }

    private void addModulePackages(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder, final Path targetResources) throws MojoExecutionException {
        debug("WfFeaturePackBuildMojo adding module packages");
        final Path layersDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS);
        if (Files.exists(layersDir)) {
            final PackageSpec.Builder modulesAll = getExtendedPackage(WfConstants.MODULES_ALL, true, targetResources);
            handleLayers(srcModulesDir, fpBuilder, targetResources, modulesAll);
        }
        final Path addOnsDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.ADD_ONS);
        if (Files.exists(addOnsDir)) {
            final PackageSpec.Builder modulesAll = getExtendedPackage(WfConstants.MODULES_ALL, true, targetResources);
            handleAddOns(srcModulesDir, fpBuilder, targetResources, modulesAll);
        }
    }

    private void addDocsSchemas(final Path fpPackagesDir, final FeaturePackDescription.Builder fpBuilder, Path targetResourcesDir)
            throws MojoExecutionException {
        getExtendedPackage(WfConstants.DOCS_SCHEMA, true, targetResourcesDir);
        getExtendedPackage(WfConstants.DOCS, true, targetResourcesDir).addPackageDep(WfConstants.DOCS_SCHEMA, true);
        final Path schemasPackageDir = fpPackagesDir.resolve(WfConstants.DOCS_SCHEMA);
        final Path schemaGroupsTxt = schemasPackageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.SCHEMA_GROUPS_TXT);
        BufferedWriter writer = null;
        try {
            Util.mkdirs(schemasPackageDir);
            Util.mkdirs(schemaGroupsTxt.getParent());
            writer = Files.newBufferedWriter(schemaGroupsTxt);
            for (String group : buildConfig.getSchemaGroups()) {
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

    private void packageContent(FeaturePackDescription.Builder fpBuilder, Path contentDir, Path packagesDir, Path targetResourcesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                final Path pkgDir = packagesDir.resolve(pkgName);
                final Path pkgContentDir = pkgDir.resolve(Constants.CONTENT).resolve(pkgName);
                final PackageSpec.Builder pkgBuilder = getExtendedPackage(pkgName, true, targetResourcesDir);
                if(pkgName.equals(WfConstants.DOCS)) {
                    try(DirectoryStream<Path> docsStream = Files.newDirectoryStream(p)) {
                        for(Path docPath : docsStream) {
                            final String docName = docPath.getFileName().toString();
                            final String docPkgName = WfConstants.DOCS + '.' + docName;
                            final Path docDir = packagesDir.resolve(docPkgName);
                            getExtendedPackage(docPkgName, true, targetResourcesDir);
                            final Path docContentDir = docDir.resolve(Constants.CONTENT).resolve(WfConstants.DOCS).resolve(docName);
                            IoUtils.copy(docPath, docContentDir);
                            pkgBuilder.addPackageDep(docPkgName, true);
                            ensureLineEndings(docContentDir);
                        }
                    }
                } else if(pkgName.equals("bin")) {
                    final Path binStandalonePkgDir = packagesDir.resolve("bin.standalone").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binDomainPkgDir = packagesDir.resolve("bin.domain").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binCommonPkgDir = packagesDir.resolve("bin.common").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binAppClientPkgDir = packagesDir.resolve("bin.appclient").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binWsToolsPkgDir = packagesDir.resolve("bin.wstools").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binVaultToolsPkgDir = packagesDir.resolve("bin.vaulttools").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path binJdrToolsPkgDir = packagesDir.resolve("bin.jdrtools").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path toolsBinPkgDir = packagesDir.resolve("tools").resolve(Constants.CONTENT).resolve(pkgName);
                    final Path coreToolsBinPkgDir = packagesDir.resolve("core-tools").resolve(Constants.CONTENT).resolve(pkgName);
                    try (DirectoryStream<Path> binStream = Files.newDirectoryStream(p)) {
                        for (Path binPath : binStream) {
                            final String fileName = binPath.getFileName().toString();
                            if(fileName.startsWith(WfConstants.STANDALONE)) {
                                IoUtils.copy(binPath, binStandalonePkgDir.resolve(fileName));
                            } else if(fileName.startsWith(WfConstants.DOMAIN)) {
                                IoUtils.copy(binPath, binDomainPkgDir.resolve(fileName));
                            } else if(fileName.startsWith("common")) {
                                IoUtils.copy(binPath, binCommonPkgDir.resolve(fileName));
                            } else if(fileName.startsWith("appclient")) {
                                IoUtils.copy(binPath, binAppClientPkgDir.resolve(fileName));
                            } else if(fileName.startsWith("ws")) {
                                IoUtils.copy(binPath, binWsToolsPkgDir.resolve(fileName));
                            } else if(fileName.startsWith("vault")) {
                                IoUtils.copy(binPath, binVaultToolsPkgDir.resolve(fileName));
                            } else if(fileName.startsWith("jdr")) {
                                IoUtils.copy(binPath, binJdrToolsPkgDir.resolve(fileName));
                            } else if (fileName.startsWith("client") || fileName.startsWith("jconsole")) {
                                IoUtils.copy(binPath, toolsBinPkgDir.resolve(fileName));
                            } else {
                                IoUtils.copy(binPath, coreToolsBinPkgDir.resolve(fileName));
                            }
                        }
                    }
                    PackageSpec.Builder toolsBuilder = null;
                    if (Files.exists(toolsBinPkgDir)) {
                        pkgBuilder.addPackageDep("tools");
                        toolsBuilder = getExtendedPackage("tools", true, targetResourcesDir);
                    }
                    PackageSpec.Builder coreToolsBuilder = null;
                    if (Files.exists(coreToolsBinPkgDir)) {
                        ensureLineEndings(coreToolsBinPkgDir);
                        coreToolsBuilder = getExtendedPackage("core-tools", true, targetResourcesDir);
                        // We want the tools package to depend on core-tools.
                        if (toolsBuilder == null) {
                            pkgBuilder.addPackageDep("tools");
                            toolsBuilder = getExtendedPackage("tools", true, targetResourcesDir);
                        }
                        toolsBuilder.addPackageDep(PackageDependencySpec.optional("core-tools"));
                    }

                    if (Files.exists(binCommonPkgDir)) {
                        ensureLineEndings(binCommonPkgDir);
                        getExtendedPackage("bin.common", true, targetResourcesDir);
                        if (coreToolsBuilder != null) {
                            coreToolsBuilder.addPackageDep(PackageDependencySpec.required("bin.common"));
                        }
                        if (toolsBuilder != null) {
                            toolsBuilder.addPackageDep(PackageDependencySpec.required("bin.common"));
                        }
                    }

                    if(Files.exists(binStandalonePkgDir)) {
                        ensureLineEndings(binStandalonePkgDir);
                        getExtendedPackage("bin.standalone", true, targetResourcesDir).addPackageDep("bin.common");
                    }
                    if(Files.exists(binDomainPkgDir)) {
                        ensureLineEndings(binDomainPkgDir);
                        getExtendedPackage("bin.domain", true, targetResourcesDir).addPackageDep(pkgName);
                    }
                    if(Files.exists(binAppClientPkgDir)) {
                        ensureLineEndings(binAppClientPkgDir);
                        getExtendedPackage("bin.appclient", true, targetResourcesDir).addPackageDep("bin.common");
                        if(toolsBuilder != null) {
                            toolsBuilder.addPackageDep(PackageDependencySpec.optional("bin.appclient"));
                        }
                    }
                    if(Files.exists(binWsToolsPkgDir)) {
                        ensureLineEndings(binWsToolsPkgDir);
                        getExtendedPackage("bin.wstools", true, targetResourcesDir).addPackageDep("bin.common");
                        if(toolsBuilder != null) {
                            toolsBuilder.addPackageDep(PackageDependencySpec.optional("bin.wstools"));
                        }
                    }
                    if(Files.exists(binVaultToolsPkgDir)) {
                        ensureLineEndings(binVaultToolsPkgDir);
                        getExtendedPackage("bin.vaulttools", true, targetResourcesDir).addPackageDep("bin.common");
                        if (coreToolsBuilder != null) {
                            coreToolsBuilder.addPackageDep(PackageDependencySpec.optional("bin.vaulttools"));
                        }
                        if (toolsBuilder != null) {
                            toolsBuilder.addPackageDep(PackageDependencySpec.optional("bin.vaulttools"));
                        }
                    }
                    if(Files.exists(binJdrToolsPkgDir)) {
                        ensureLineEndings(binJdrToolsPkgDir);
                        getExtendedPackage("bin.jdrtools", true, targetResourcesDir).addPackageDep("bin.common");
                        if(toolsBuilder != null) {
                            toolsBuilder.addPackageDep(PackageDependencySpec.optional("bin.jdrtools"));
                        }
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
