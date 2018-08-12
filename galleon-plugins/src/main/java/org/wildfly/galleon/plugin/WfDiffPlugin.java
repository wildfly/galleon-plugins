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



import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.diff.FileSystemDiff;
import org.jboss.galleon.diff.ProvisioningDiffResult;
import org.jboss.galleon.plugin.DiffPlugin;
import org.jboss.galleon.plugin.PluginOption;
import org.jboss.galleon.plugin.ProvisioningPluginWithOptions;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.PathFilter;

/**
 * WildFly plugin to compute the model difference between an instance and a clean provisioned instance.
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class WfDiffPlugin extends ProvisioningPluginWithOptions implements DiffPlugin {

    //private static final String WF_DIFF_CONFIG_GENERATOR = "org.wildfly.galleon.plugin.config.generator.WfDiffConfigGenerator";
    //private static final String WF_DIFF_CONFIG_GENERATOR = "org.wildfly.galleon.plugin.config.generator.WfProvisionedStateDiff";
    private static final String WF_DIFF_CONFIG_GENERATOR = "org.wildfly.galleon.plugin.config.generator.WfConfigsReader";

    private static final PathFilter FILTER_FP = PathFilter.Builder.instance()
            .addDirectories("*" + File.separatorChar + "tmp", "*" + File.separatorChar + "log", "*_xml_history", "model_diff")
            .addFiles("standalone.xml", "process-uuid", "logging.properties")
            .build();

    private static final PathFilter FILTER = PathFilter.Builder.instance()
            .addDirectories("*" + File.separatorChar + "tmp", "model_diff")
            .addFiles("standalone.xml", "logging.properties")
            .build();

    public static final PluginOption HOST = PluginOption.builder("host").setDefaultValue("127.0.0.1").build();
    public static final PluginOption PORT = PluginOption.builder("port").setDefaultValue("9990").build();
    public static final PluginOption PROTOCOL = PluginOption.builder("protocol").setDefaultValue("remote+http").build();
    public static final PluginOption USERNAME = PluginOption.builder("username").setDefaultValue("galleon").build();
    public static final PluginOption PASSWORD = PluginOption.builder("password").setDefaultValue("galleon").build();
    public static final PluginOption SERVER_CONFIG = PluginOption.builder("server-config").setDefaultValue("standalone.xml").build();

    private MavenRepoManager maven;

    @Override
    protected List<PluginOption> initPluginOptions() {
        return Arrays.asList(
                HOST,
                PORT,
                PROTOCOL,
                USERNAME,
                PASSWORD,
                SERVER_CONFIG);
    }

    @Override
    public ProvisioningDiffResult computeDiff(ProvisioningRuntime runtime, Path customizedInstallation, Path target) throws ProvisioningException {
        final MessageWriter log = runtime.getMessageWriter();
        log.verbose("WildFly diff plug-in");
        FileSystemDiff diff = new FileSystemDiff(log, runtime.getStagedDir(), customizedInstallation);
        final Path configGenJar = runtime.getResource("wildfly/wildfly-config-gen.jar");
        if(!Files.exists(configGenJar)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(configGenJar));
        }

        maven = (MavenRepoManager) runtime.getArtifactResolver(MavenRepoManager.REPOSITORY_ID);
        final Map<String, String> artifactVersions = getArtifactVersions(runtime);


        final URL[] cp = new URL[4];
        try {
            cp[0] = configGenJar.toUri().toURL();
            MavenArtifact artifact = Utils.toArtifactCoords(artifactVersions, "org.jboss.modules:jboss-modules", false);
            maven.resolve(artifact);
            cp[1] = artifact.getPath().toUri().toURL();
            artifact = Utils.toArtifactCoords(artifactVersions, "org.wildfly.core:wildfly-cli::client", false);
            maven.resolve(artifact);
            cp[2] = artifact.getPath().toUri().toURL();
            artifact = Utils.toArtifactCoords(artifactVersions, "org.jboss.core:wildfly-launcher", false);
            maven.resolve(artifact);
            cp[3] = artifact.getPath().toUri().toURL();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to init classpath for " + runtime.getStagedDir(), e);
        }
        if(log.isVerboseEnabled()) {
            log.verbose("Config diff generator classpath:");
            for(int i = 0; i < cp.length; ++i) {
                log.verbose(i+1 + ". " + cp[i]);
            }
        }

        List<ConfigModel> configs;
        Map<FPID, ConfigId> includedConfigs = new HashMap<>();
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final URLClassLoader configGenCl = new URLClassLoader(cp, originalCl);
        Thread.currentThread().setContextClassLoader(configGenCl);
        try {
            final Class<?> wfDiffGenerator = configGenCl.loadClass(WF_DIFF_CONFIG_GENERATOR);
            final Method exportDiff = wfDiffGenerator.getMethod("exportDiff", ProvisioningRuntime.class, Map.class, Path.class, Path.class);
            configs = (List<ConfigModel>) exportDiff.invoke(null, runtime, includedConfigs, customizedInstallation, target);
        } catch(InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if(cause instanceof ProvisioningException) {
                throw (ProvisioningException)cause;
            } else {
                throw new ProvisioningException("Failed to invoke config diff generator " + WF_DIFF_CONFIG_GENERATOR, cause);
            }
        } catch (Throwable e) {
            throw new ProvisioningException("Failed to initialize config diff generator " + WF_DIFF_CONFIG_GENERATOR, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            try {
                configGenCl.close();
            } catch (IOException e) {
            }
        }

        return new WfDiffResult(includedConfigs,
                configs,
                // Collections.singletonList(target.resolve("finalize.cli").toAbsolutePath()),
                Collections.emptyList(),
                diff.diff(getFilter(runtime)));
    }

    private PathFilter getFilter(ProvisioningRuntime runtime) {
        //if ("diff-to-feature-pack".equals(runtime.getOperation())) {
        //    return FILTER_FP;
        //}
        return FILTER;
    }

    private Map<String, String> getArtifactVersions(ProvisioningRuntime runtime) throws ProvisioningException {
        final Map<String, String> artifactVersions = new HashMap<>();
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }
            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            if(!Files.exists(artifactProps)) {
                continue;
            }
            Utils.readProperties(artifactProps, artifactVersions);
        }
        return artifactVersions;
    }
}
