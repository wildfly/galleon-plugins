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
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;

import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.ArtifactException;
import org.jboss.galleon.Errors;
import org.jboss.galleon.Gav;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.plugin.InstallPlugin;
import org.jboss.galleon.plugin.PluginOption;
import org.jboss.galleon.plugin.ProvisioningPluginWithOptions;
import org.jboss.galleon.progresstracking.ProgressTracker;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.galleon.plugin.config.CopyArtifact;
import org.wildfly.galleon.plugin.config.CopyPath;
import org.wildfly.galleon.plugin.config.DeletePath;
import org.wildfly.galleon.plugin.config.ExampleFpConfigs;
import org.wildfly.galleon.plugin.config.XslTransform;
import org.wildfly.galleon.plugin.server.CliScriptRunner;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfInstallPlugin extends ProvisioningPluginWithOptions implements InstallPlugin {

    private static final String JAR = "jar";
    private static final String CONFIG_GEN_METHOD = "generate";
    private static final String CONFIG_GEN_PATH = "wildfly/wildfly-config-gen.jar";
    private static final String CONFIG_GEN_CLASS = "org.wildfly.galleon.plugin.config.generator.WfConfigGenerator";

    private static final PluginOption OPTION_MVN_DIST = PluginOption.builder("jboss-maven-dist").hasNoValue().build();
    public static final PluginOption OPTION_DUMP_CONFIG_SCRIPTS = PluginOption.builder("jboss-dump-config-scripts").build();
    private static final PluginOption OPTION_FORK_EMBEDDED = PluginOption.builder("jboss-fork-embedded").build();

    private ProvisioningRuntime runtime;
    private MessageWriter log;
    private PropertyResolver versionResolver;

    private Map<FPID, Properties> fpTasksProps = Collections.emptyMap();
    private Properties mergedTaskProps = new Properties();
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

    @Override
    protected List<PluginOption> initPluginOptions() {
        return Arrays.asList(OPTION_MVN_DIST, OPTION_DUMP_CONFIG_SCRIPTS, OPTION_FORK_EMBEDDED);
    }

    public ProvisioningRuntime getRuntime() {
        return runtime;
    }

    /* (non-Javadoc)
     * @see org.jboss.galleon.util.plugin.ProvisioningPlugin#execute()
     */
    @Override
    public void postInstall(ProvisioningRuntime runtime) throws ProvisioningException {

        this.runtime = runtime;
        log = runtime.getMessageWriter();
        log.verbose("WildFly Galleon Installation Plugin");

        thinServer = runtime.isOptionSet(OPTION_MVN_DIST);

        final Map<String, String> artifactVersions = new HashMap<>();
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }

            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            if(Files.exists(artifactProps)) {
                try (Stream<String> lines = Files.lines(artifactProps)) {
                    final Iterator<String> iterator = lines.iterator();
                    while (iterator.hasNext()) {
                        final String line = iterator.next();
                        final int i = line.indexOf('=');
                        if (i < 0) {
                            throw new ProvisioningException("Failed to locate '=' character in " + line);
                        }
                        artifactVersions.put(line.substring(0, i), line.substring(i + 1));
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(artifactProps), e);
                }
            }

            final Path tasksPropsPath = wfRes.resolve(WfConstants.WILDFLY_TASKS_PROPS);
            if(Files.exists(tasksPropsPath)) {
                final Properties fpProps = new Properties();
                try(InputStream in = Files.newInputStream(tasksPropsPath)) {
                    fpProps.load(in);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(tasksPropsPath), e);
                }
                fpTasksProps = CollectionUtils.put(fpTasksProps, fp.getFPID(), fpProps);
                mergedTaskProps.putAll(fpProps);
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
        mergedTaskPropsResolver = new MapPropertyResolver(mergedTaskProps);
        versionResolver = new MapPropertyResolver(artifactVersions);

        pkgProgressTracker = runtime.getLayoutFactory().getProgressTracker(ProvisioningLayoutFactory.TRACK_PACKAGES);
        long pkgsTotal = 0;
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            pkgsTotal += fp.getPackageNames().size();
        }
        pkgProgressTracker.starting(pkgsTotal);
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            processPackages(fp);
        }
        pkgProgressTracker.complete();

        final Path layersConf = runtime.getStagedDir().resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
        if(Files.exists(layersConf)) {
            mergeLayerConfs(runtime);
        }

        generateConfigs(runtime);

        // TODO this needs to be revisited
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path finalizeCli = fp.getResource(WfConstants.WILDFLY, WfConstants.SCRIPTS, "finalize.cli");
            if(Files.exists(finalizeCli)) {
                CliScriptRunner.runCliScript(runtime.getStagedDir(), finalizeCli, log);
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
    }

    private void mergeLayerConfs(ProvisioningRuntime runtime) throws ProvisioningException {
        List<Path> layersConfs = Collections.emptyList();
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final PackageRuntime layersConfPkg = fp.getPackage(WfConstants.LAYERS_CONF);
            if(layersConfPkg == null) {
                continue;
            }
            final Path layersConf = layersConfPkg.getContentDir().resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
            if(!Files.exists(layersConf)) {
                throw new ProvisioningException(
                        "Feature-pack " + fp.getFPID() + " package " + WfConstants.LAYERS_CONF + " is expected to contain "
                                + WfConstants.MODULES + "/" + WfConstants.LAYERS_CONF + " but it does not");
            }
            layersConfs = CollectionUtils.add(layersConfs, layersConf);
        }
        if(layersConfs.size() < 2) {
            return;
        }
        final Properties props = new Properties();
        final Set<String> layers = new LinkedHashSet<>(layersConfs.size());
        for(Path p : layersConfs) {
            try(BufferedReader reader = Files.newBufferedReader(p)) {
                props.load(reader);
            } catch (IOException e) {
                throw new ProvisioningException("Failed to generate layer.conf", e);
            }
            String layersProp = props.getProperty(WfConstants.LAYERS);
            if (layersProp == null || (layersProp = layersProp.trim()).length() == 0) {
                continue;
            }
            final String[] layerNames = layersProp.split(",");
            for(String layerName : layerNames) {
                layers.add(layerName);
            }
        }
        if(!layers.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, layers);
            props.setProperty(WfConstants.LAYERS, buf.toString());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(runtime.getStagedDir().resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF))) {
            props.store(writer, "Generated by WildFly Galleon provisioning plugin");
        }
        catch (IOException e) {
            throw new ProvisioningException("Failed to persist generated layers.conf", e);
        }
    }

    private void provisionExampleConfigs() throws ProvisioningException {

        final Path examplesTmp = runtime.getTmpPath("example-configs");
        final ProvisioningManager pm = ProvisioningManager.builder()
                .setInstallationHome(examplesTmp)
                .setMessageWriter(log)
                .setLayoutFactory(runtime.getLayoutFactory())
                .build();

        List<Path> configPaths = new ArrayList<>();
        final ProvisioningConfig.Builder configBuilder = ProvisioningConfig.builder();
        for(FeaturePackRuntime fpRt : runtime.getFeaturePacks()) {
            final FeaturePackConfig.Builder fpBuilder = FeaturePackConfig.builder(fpRt.getFPID().getLocation())
                    .setInheritConfigs(false)
                    .setInheritPackages(false);
            final ExampleFpConfigs fpExampleConfigs = exampleConfigs.get(fpRt.getFPID());
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
            Map<String, String> options = runtime.getPluginOptions();
            if(!options.containsKey(OPTION_MVN_DIST.getName())) {
                final Map<String, String> tmp = new HashMap<>(options.size() + 1);
                tmp.putAll(options);
                options = tmp;
                options.put(OPTION_MVN_DIST.getName(), null);
            }
            pm.provision(config, options);
        } catch(ProvisioningException e) {
            throw new ProvisioningException("Failed to generate example configs", e);
        }

        final Path exampleConfigsDir = runtime.getStagedDir().resolve(WfConstants.DOCS).resolve("examples").resolve("configs");
        for(Path configPath : configPaths) {
            try {
                IoUtils.copy(configPath, exampleConfigsDir.resolve(configPath.getFileName()));
            } catch (IOException e) {
                throw new ProvisioningException(Errors.copyFile(configPath, exampleConfigsDir.resolve(configPath.getFileName())), e);
            }
        }
    }

    private void generateConfigs(ProvisioningRuntime runtime) throws ProvisioningException {
        if(!runtime.hasConfigs()) {
            return;
        }

        final Path configGenJar = runtime.getResource(CONFIG_GEN_PATH);
        if(!Files.exists(configGenJar)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(configGenJar));
        }

        final URL[] cp = new URL[3];
        try {
            cp[0] = configGenJar.toUri().toURL();
            Gav gav = Gav.parse(resolveRequiredGav("org.jboss.modules:jboss-modules"));
            cp[1] = runtime.resolveArtifact(new ArtifactCoords(gav.getGroupId(), gav.getArtifactId(), gav.getVersion(), null, JAR)).toUri().toURL();
            gav = Gav.parse(resolveRequiredGav("org.wildfly.core:wildfly-cli"));
            cp[2] = runtime.resolveArtifact(new ArtifactCoords(gav.getGroupId(), gav.getArtifactId(), gav.getVersion(), "client", JAR)).toUri().toURL();
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
            final Method m = configHandlerCls.getMethod(CONFIG_GEN_METHOD, ProvisioningRuntime.class, boolean.class);
            final Object generator = ctor.newInstance();
            boolean forkEmbedded = false;
            if(runtime.isOptionSet(OPTION_FORK_EMBEDDED)) {
                final String value = runtime.getOptionValue(OPTION_FORK_EMBEDDED);
                forkEmbedded = value == null ? true : Boolean.parseBoolean(value);
            }
            m.invoke(generator, runtime, forkEmbedded);
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

    private String resolveRequiredGav(String artifactGa) throws ProvisioningException {
        String gavStr = versionResolver.resolveProperty(artifactGa);
        if(gavStr == null) {
            throw new ProvisioningException("Failed to resolve version of " + artifactGa);
        }
        return gavStr;
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
                processModules(fp.getFPID(), pkg.getName(), moduleDir);
            }
            final Path tasksXml = pmWfDir.resolve(WfConstants.TASKS_XML);
            if(!Files.exists(tasksXml)) {
                pkgProgressTracker.processed(pkg);
                continue;
            }
            final WildFlyPackageTasks pkgTasks = WildFlyPackageTasks.load(tasksXml);
            if (pkgTasks.hasTasks()) {
                log.verbose("Processing %s package %s tasks", fp.getFPID(), pkg.getName());
                for(WildFlyPackageTask task : pkgTasks.getTasks()) {
                    if(task.getPhase() == WildFlyPackageTask.Phase.PROCESSING) {
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
            pkgProgressTracker.processed(pkg);
        }
    }

    public void xslTransform(FeaturePackRuntime fp, XslTransform xslt, Path pmWfDir) throws ProvisioningException {

        final Path src = runtime.getStagedDir().resolve(xslt.getSrc());
        if (!Files.exists(src)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(src));
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
            final Properties taskProps = fpTasksProps.get(fp.getFPID());
            if (taskProps != null) {
                for (Map.Entry<Object, Object> prop : taskProps.entrySet()) {
                    transformer.setParameter(prop.getKey().toString(), prop.getValue());
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

    private void processModules(FPID fp, String pkgName, Path fpModuleDir) throws ProvisioningException {
        try {
            final Path installDir = runtime.getStagedDir();
            Files.walkFileTree(fpModuleDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                    final Path targetDir = installDir.resolve(fpModuleDir.relativize(dir));
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
                        processModuleTemplate(fpModuleDir, installDir, file);
                    } else {
                        Files.copy(file, installDir.resolve(fpModuleDir.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProvisioningException("Failed to process modules from package " + pkgName + " from feature-pack " + fp, e);
        }
    }

    private void processModuleTemplate(Path fpModuleDir, final Path installDir, Path moduleTemplate) throws IOException {
        final Builder builder = new Builder(false);
        final Document document;
        try (BufferedReader reader = Files.newBufferedReader(moduleTemplate, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        final Path targetPath = installDir.resolve(fpModuleDir.relativize(moduleTemplate));
        final Element rootElement = document.getRootElement();
        if (! rootElement.getLocalName().equals("module") &&
                // module-alias files don't need to be processed
                // the only reason their are parsed and serialized is to match the processing in the legacy build tools
                // this fixes the difference in lineendings between the two builds
                !rootElement.getLocalName().equals("module-alias")) {
            // just copy the content and leave
            Files.copy(moduleTemplate, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // replace version, if any
        final Attribute versionAttribute = rootElement.getAttribute("version");
        if (versionAttribute != null) {
            final String versionExpr = versionAttribute.getValue();
            if (versionExpr.startsWith("${") && versionExpr.endsWith("}")) {
                final String exprBody = versionExpr.substring(2, versionExpr.length() - 1);
                final int optionsIndex = exprBody.indexOf('?');
                final String artifactName;
                if (optionsIndex > 0) {
                    artifactName = exprBody.substring(0, optionsIndex);
                } else {
                    artifactName = exprBody;
                }
                final String resolved = versionResolver.resolveProperty(artifactName);
                if (resolved != null) {
                    try {
                        versionAttribute.setValue(toArtifactCoords(resolved, false).getVersion());
                    } catch (ProvisioningException e) {
                        throw new IOException("Failed to resolve artifact coordinates for " + resolved, e);
                    }
                }
            }
        }
        // replace all artifact declarations
        final Element resourcesElement = rootElement.getFirstChildElement("resources", rootElement.getNamespaceURI());
        if (resourcesElement != null) {
            final Elements artifacts = resourcesElement.getChildElements("artifact", rootElement.getNamespaceURI());
            final int artifactCount = artifacts.size();
            for (int i = 0; i < artifactCount; i ++) {
                final Element element = artifacts.get(i);
                assert element.getLocalName().equals("artifact");
                final Attribute attribute = element.getAttribute("name");
                String coordsStr = attribute.getValue();
                boolean jandex = false;
                if (coordsStr.startsWith("${") && coordsStr.endsWith("}")) {
                    coordsStr = coordsStr.substring(2, coordsStr.length() - 1);
                    final int optionsIndex = coordsStr.indexOf('?');
                    if (optionsIndex >= 0) {
                        jandex = coordsStr.indexOf("jandex", optionsIndex) >= 0;
                        coordsStr = coordsStr.substring(0, optionsIndex);
                    }
                    coordsStr = versionResolver.resolveProperty(coordsStr);
                }

                if(coordsStr == null) {
                    continue;
                }

                ArtifactCoords coords;
                try {
                    coords = toArtifactCoords(coordsStr, false);
                } catch (ProvisioningException e) {
                    throw new IOException("Failed to resolve full coordinates for " + coordsStr, e);
                }
                final Path moduleArtifact;

                log.verbose("Resolving %s", coords);
                try {
                    moduleArtifact = runtime.resolveArtifact(coords);
                } catch (ArtifactException e) {
                    throw new IOException("Failed to resolve artifact " + coords, e);
                }

                if (thinServer) {
                    // ignore jandex variable, just resolve coordinates to a string
                    attribute.setValue(coordsStr);
                } else {
                    final Path targetDir = installDir.resolve(fpModuleDir.relativize(moduleTemplate.getParent()));
                    final String artifactFileName = moduleArtifact.getFileName().toString();
                    final String finalFileName;

                    if (jandex) {
                        final int lastDot = artifactFileName.lastIndexOf(".");
                        final File target = new File(targetDir.toFile(),
                                new StringBuilder().append(artifactFileName.substring(0, lastDot)).append("-jandex")
                                        .append(artifactFileName.substring(lastDot)).toString());
                        JandexIndexer.createIndex(moduleArtifact.toFile(), new FileOutputStream(target), log);
                        finalFileName = target.getName();
                    } else {
                        finalFileName = artifactFileName;
                        final Path targetModulePath = targetDir.resolve(artifactFileName);
                        Files.copy(moduleArtifact, targetModulePath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    element.setLocalName("resource-root");
                    attribute.setLocalName("path");
                    attribute.setValue(finalFileName);
                }
                if (schemaGroups.contains(coords.getGroupId())) {
                    extractSchemas(moduleArtifact);
                }
            }
        }
        // now serialize the result
        try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
            new Serializer(outputStream).write(document);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
            throw t;
        }
    }

    public void addExampleConfigs(FeaturePackRuntime fp, ExampleFpConfigs exampleConfigs) throws ProvisioningDescriptionException {
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
        try (FileSystem jarFS = FileSystems.newFileSystem(moduleArtifact, null)) {
            final Path schemaSrc = jarFS.getPath(WfConstants.SCHEMA);
            if (Files.exists(schemaSrc)) {
                ZipUtils.copyFromZip(schemaSrc.toAbsolutePath(), targetSchemasDir);
            }
        }
    }

    public void copyArtifact(CopyArtifact copyArtifact) throws ProvisioningException, ArtifactException {
        final ArtifactCoords coords = toArtifactCoords(copyArtifact.getArtifact(), copyArtifact.isOptional());
        if(coords == null) {
            return;
        }
        try {

            log.verbose("Resolving artifact %s ", coords);
            final Path jarSrc = this.runtime.resolveArtifact(coords);
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
                extractArtifact(jarSrc, jarTarget, copyArtifact);
            } else {
                IoUtils.copy(jarSrc, jarTarget);
            }
            if(schemaGroups.contains(coords.getGroupId())) {
                extractSchemas(jarSrc);
            }
        } catch (IOException e) {
            throw new ProvisioningException("Failed to copy artifact " + coords, e);
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
                                final Path targetDir = target.resolve(src.relativize(dir));
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
                                PropertyReplacer.copy(file, target.resolve(src.relativize(file)), mergedTaskPropsResolver);
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
        } else {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new ProvisioningException(Errors.deletePath(path), e);
            }
        }
    }

    private void extractArtifact(Path artifact, Path target, CopyArtifact copy) throws IOException {
        if(!Files.exists(target)) {
            Files.createDirectories(target);
        }
        try (FileSystem zipFS = FileSystems.newFileSystem(artifact, null)) {
            for(Path zipRoot : zipFS.getRootDirectories()) {
                Files.walkFileTree(zipRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                                String entry = dir.toString().substring(1);
                                if(entry.isEmpty()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if(!entry.endsWith("/")) {
                                    entry += '/';
                                }
                                if(!copy.includeFile(entry)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                final Path targetDir = target.resolve(zipRoot.relativize(dir).toString());
                                try {
                                    Files.copy(dir, targetDir);
                                } catch (FileAlreadyExistsException e) {
                                     if (!Files.isDirectory(targetDir))
                                         throw e;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                                if(copy.includeFile(file.toString().substring(1))) {
                                    final Path targetPath = target.resolve(zipRoot.relativize(file).toString());
                                    Files.copy(file, targetPath);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            }
        }
    }

    private static void mkdirs(final WildFlyPackageTasks tasks, Path installDir) throws ProvisioningException {
        // make dirs
        for (String dirName : tasks.getMkDirs()) {
            final Path dir = installDir.resolve(dirName);
            if(!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.mkdirs(dir));
                }
            }
        }
    }

    private ArtifactCoords toArtifactCoords(String str, boolean optional) throws ProvisioningException {

        String[] parts = str.split(":");
        if(parts.length < 2) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = null;
        String classifier = null;
        String ext = JAR;
        if(parts.length > 2) {
            if(!parts[2].isEmpty()) {
                version = parts[2];
            }
            if(parts.length > 3) {
                classifier = parts[3];
                if(parts.length > 4 && !parts[4].isEmpty()) {
                    ext = parts[4];
                    if (parts.length > 5) {
                        throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
                    }
                }
            }
        }

        if(version != null) {
            return new ArtifactCoords(groupId, artifactId, version, classifier, ext);
        }

        final String resolvedStr = versionResolver.resolveProperty(groupId + ':' + artifactId);
        if (resolvedStr == null) {
            if (optional) {
                return null;
            }
            throw new ProvisioningException("Failed to resolve the version of " + str);
        }

        parts = resolvedStr.split(":");
        if(parts.length < 3) {
            throw new ProvisioningException("Failed to resolve version for artifact: " + resolvedStr);
        }
        return new ArtifactCoords(groupId, artifactId, parts[2], classifier, ext);
    }
}
