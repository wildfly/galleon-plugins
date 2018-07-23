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
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;

import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.diff.FileSystemDiff;
import org.jboss.galleon.plugin.DiffPlugin;
import org.jboss.galleon.plugin.PluginOption;
import org.jboss.galleon.plugin.ProvisioningPluginWithOptions;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.util.PathFilter;
import org.jboss.galleon.xml.ConfigXmlWriter;
import org.wildfly.galleon.plugin.server.ClassLoaderHelper;

/**
 * WildFly plugin to compute the model difference between an instance and a clean provisioned instance.
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class WfDiffPlugin extends ProvisioningPluginWithOptions implements DiffPlugin {

    private static final PathFilter FILTER_FP = PathFilter.Builder.instance()
            .addDirectories("*" + File.separatorChar + "tmp", "*" + File.separatorChar + "log", "*_xml_history", "model_diff")
            .addFiles("standalone.xml", "process-uuid", "logging.properties")
            .build();

    private static final PathFilter FILTER = PathFilter.Builder.instance()
            .addDirectories("*" + File.separatorChar + "tmp", "model_diff")
            .addFiles("standalone.xml", "logging.properties")
            .build();

    @Override
    protected List<PluginOption> initPluginOptions() {
        return Arrays.asList(
                WfDiffConfigGenerator.HOST,
                WfDiffConfigGenerator.PORT,
                WfDiffConfigGenerator.PROTOCOL,
                WfDiffConfigGenerator.USERNAME,
                WfDiffConfigGenerator.PASSWORD,
                WfDiffConfigGenerator.SERVER_CONFIG);
    }

    @Override
    public void computeDiff(ProvisioningRuntime runtime, Path customizedInstallation, Path target) throws ProvisioningException {
        final MessageWriter messageWriter = runtime.getMessageWriter();
        messageWriter.verbose("WildFly diff plug-in");
        //FileSystemDiff diff = new FileSystemDiff(messageWriter, runtime.getInstallDir(), customizedInstallation);
        FileSystemDiff diff = new FileSystemDiff(messageWriter, runtime.getStagedDir(), customizedInstallation);
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        //URLClassLoader newCl = ClassLoaderHelper.prepareProvisioningClassLoader(runtime.getInstallDir(), originalCl);
        URLClassLoader newCl = ClassLoaderHelper.prepareProvisioningClassLoader(runtime.getStagedDir(), originalCl);
        Properties props = System.getProperties();
        ConfigModel config;
        Thread.currentThread().setContextClassLoader(newCl);
        Map<FPID, ConfigId> includedConfigs = new HashMap<>();
        try {
            final Class<?> wfDiffGenerator = newCl.loadClass("org.jboss.galleon.plugin.wildfly.WfDiffConfigGenerator");
            final Method exportDiff = wfDiffGenerator.getMethod("exportDiff", ProvisioningRuntime.class, Map.class, Path.class, Path.class);
            config = (ConfigModel) exportDiff.invoke(null, runtime, includedConfigs, customizedInstallation, target);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoClassDefFoundError ex) {
            throw new ProvisioningException(ex.getMessage(), ex);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            System.setProperties(props);
            ClassLoaderHelper.close(newCl);
        }
        try {
            ConfigXmlWriter.getInstance().write(config, target.resolve("config.xml"));
            WfDiffResult result = new WfDiffResult(
                    includedConfigs,
                    Collections.singletonList(config),
//                    Collections.singletonList(target.resolve("finalize.cli").toAbsolutePath()),
                    Collections.emptyList(),
                    diff.diff(getFilter(runtime)));
            runtime.setDiff(result.merge(runtime.getDiff()));
        } catch (IOException | XMLStreamException ex) {
            messageWriter.error(ex, "Couldn't compute the WildFly Model diff because of %s", ex.getMessage());
            Logger.getLogger(WfDiffPlugin.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private PathFilter getFilter(ProvisioningRuntime runtime) {
        if ("diff-to-feature-pack".equals(runtime.getOperation())) {
            return FILTER_FP;
        }
        return FILTER;
    }
}
