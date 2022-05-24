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

package org.wildfly.galleon.plugin.config.generator;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.progresstracking.ProgressTracker;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.state.ProvisionedConfig;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ForkedEmbeddedUtil;
import org.wildfly.galleon.plugin.server.ConfigGeneratorException;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfConfigGenerator extends BaseConfigGenerator {

    private MessageWriter messageWriter;

    public void generate(ProvisioningRuntime runtime, boolean forkEmbedded) throws ProvisioningException {
        this.messageWriter = runtime.getMessageWriter();
        this.forkEmbedded = forkEmbedded;
        this.jbossHome = runtime.getStagedDir().toString();
        final Map<?, ?> originalProps = forkEmbedded ? null : new HashMap<>(System.getProperties());
        try {
            doGenerate(runtime);
        } finally {
            cleanup(originalProps);
        }
    }

    private void doGenerate(ProvisioningRuntime runtime) throws ProvisioningException {

        if(messageWriter.isVerboseEnabled()) {
            messageWriter.verbose("Generating WildFly-based configs forkEmbedded=%s", forkEmbedded);
        }

        if(forkEmbedded) {
            initScriptWriter(runtime);
        }

        final ProgressTracker<ProvisionedConfig> progressTracker = runtime.getLayout().getFactory()
                .getProgressTracker(ProvisioningLayoutFactory.TRACK_CONFIGS);

        try(WfProvisionedConfigHandler configHandler = new WfProvisionedConfigHandler(runtime, this)) {
            final List<ProvisionedConfig> configs = runtime.getConfigs();
            progressTracker.starting(configs.size());
            for (ProvisionedConfig config : configs) {
                progressTracker.processing(config);
                if (runtime.getMessageWriter().isVerboseEnabled()) {
                    final StringBuilder msg = new StringBuilder(64).append("Feature config");
                    if (config.getModel() != null) {
                        msg.append(" model=").append(config.getModel());
                    }
                    if (config.getName() != null) {
                        msg.append(" name=").append(config.getName());
                    }
                    messageWriter.verbose(msg);
                    if (config.hasProperties()) {
                        messageWriter.verbose("  properties");
                        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
                            messageWriter.verbose("    %s=%s", entry.getKey(), entry.getValue());
                        }
                    }
                }
                config.handle(configHandler);
                progressTracker.processed(config);
            }
            progressTracker.complete();
        }

        if(forkEmbedded) {
            scriptWriter.close();
            scriptWriter = null;
            ForkedEmbeddedUtil.fork(new ForkedConfigGenerator(), jbossHome, script.toString());
        }
    }

    private void cleanup(Map<?, ?> originalProps) {
        if (embeddedProcess != null) {
            try {
                stopEmbedded();
            } catch (ConfigGeneratorException e) {
                e.printStackTrace();
            }
        }
        if (scriptWriter != null) {
            scriptWriter.close();
        } else if(originalProps != null) {
            final List<String> toClear = new ArrayList<>();
            for (Map.Entry<?, ?> prop : System.getProperties().entrySet()) {
                final Object value = originalProps.get(prop.getKey());
                if (value != null) {
                    System.setProperty(prop.getKey().toString(), value.toString());
                } else {
                    toClear.add(prop.getKey().toString());
                }
            }
            if (!toClear.isEmpty()) {
                for (String prop : toClear) {
                    System.clearProperty(prop);
                }
            }
        }
    }

    private void initScriptWriter(ProvisioningRuntime runtime) throws ProvisioningException {
        scriptBuf = new StringBuilder();
        script = runtime.getTmpPath("forkedembedded.txt");
        try {
            Files.createDirectories(script.getParent());
            scriptWriter = new PrintWriter(Files.newBufferedWriter(script));
        } catch (IOException e) {
            throw new ProvisioningException(Errors.writeFile(script), e);
        }
    }

    void startServer(String... args) throws ProvisioningException {
        try {
            if (forkEmbedded) {
                writeScript(WfConstants.STANDALONE);
                writeArgs(args);
            } else {
                doStartServer(args);
            }
        } catch (ConfigGeneratorException e) {
            throw new ProvisioningException(e);
        }
    }

    void startHc(String... args) throws ProvisioningException {
        try {
            if (forkEmbedded) {
                writeScript(WfConstants.HOST);
                writeArgs(args);
            } else {
                doStartHc(args);
            }
        } catch (ConfigGeneratorException e) {
            throw new ProvisioningException(e);
        }
    }

    private void writeArgs(String... args) throws ConfigGeneratorException {
        scriptBuf.setLength(0);
        scriptBuf.append(args[0]);
        for(int i = 1; i < args.length; ++i) {
            scriptBuf.append(',').append(args[i]);
        }
        writeScript(scriptBuf.toString());
    }
}
