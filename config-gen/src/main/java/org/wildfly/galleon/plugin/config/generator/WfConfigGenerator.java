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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.state.ProvisionedConfig;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfConfigGenerator {

    private static final byte INITIAL = 0;
    private static final byte START_STANDALONE = 1;
    private static final byte START_HC = 2;
    private static final byte LOOKING_FOR_ARGS = 4;
    private static final byte EMBEDDED_STARTED = 8;

    private static final String BATCH = "batch";
    private static final String STOP = "stop";
    private static final String RUN_BATCH = "run-batch";

    private Long bootTimeout = null;

    private boolean forkEmbedded;
    private String jbossHome;
    private EmbeddedManagedProcess embeddedProcess;
    private ModelControllerClient mcc;
    private ModelNode composite;

    private boolean hc;
    private String[] args;

    private Path script;
    private PrintWriter scriptWriter;
    private StringBuilder scriptBuf;

    public void generate(ProvisioningRuntime runtime, boolean forkEmbedded) throws ProvisioningException {
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

        final MessageWriter messageWriter = runtime.getMessageWriter();
        if(messageWriter.isVerboseEnabled()) {
            messageWriter.verbose("Generating WildFly-based configs forkEmbedded=%", forkEmbedded);
        }

        if(forkEmbedded) {
            initScriptWriter(runtime);
        }

        try(WfProvisionedConfigHandler configHandler = new WfProvisionedConfigHandler(runtime, this)) {
            for (ProvisionedConfig config : runtime.getConfigs()) {
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
            }
        }

        if(forkEmbedded) {
            scriptWriter.close();
            scriptWriter = null;
            forkEmbedded(messageWriter);
        }
    }

    private void cleanup(Map<?, ?> originalProps) {
        if (embeddedProcess != null) {
            try {
                stopEmbedded();
            } catch (ProvisioningException e) {
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
        if(forkEmbedded) {
            writeScript(WfConstants.STANDALONE);
            writeArgs(args);
        } else {
            doStartServer(args);
        }
    }

    private void doStartServer(String... args) throws ProvisioningException {
        //System.out.println("embed server " + jbossHome + " " + Arrays.asList(args));
        this.args = args;
        this.hc = false;
        embeddedProcess = EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, args);
        try {
            embeddedProcess.start();
        } catch (EmbeddedProcessStartException e) {
            throw new ProvisioningException("Failed to start embedded server", e);
        }
        mcc = embeddedProcess.getModelControllerClient();
        waitForServer();
    }

    void startHc(String... args) throws ProvisioningException {
        if(forkEmbedded) {
            writeScript(WfConstants.HOST);
            writeArgs(args);
        } else {
            doStartHc(args);
        }
    }

    private void writeArgs(String... args) throws ProvisioningException {
        scriptBuf.setLength(0);
        scriptBuf.append(args[0]);
        for(int i = 1; i < args.length; ++i) {
            scriptBuf.append(',').append(args[i]);
        }
        writeScript(scriptBuf.toString());
    }

    private void doStartHc(String... args) throws ProvisioningException {
        //System.out.println("embed hc " + jbossHome + " " + Arrays.asList(args));
        this.args = args;
        this.hc = true;
        embeddedProcess = EmbeddedProcessFactory.createHostController(jbossHome, null, null, args);
        try {
            embeddedProcess.start();
        } catch (EmbeddedProcessStartException e) {
            throw new ProvisioningException("Failed to start embedded hc", e);
        }
        mcc = embeddedProcess.getModelControllerClient();
        //waitForHc();
    }

    void stopEmbedded() throws ProvisioningException {
        if(forkEmbedded) {
            writeScript(STOP);
        } else {
            doStopEmbedded();
        }
    }

    private void doStopEmbedded() throws ProvisioningException {
        //System.out.println("stop embedded");
        if(mcc != null) {
            try {
                mcc.close();
            } catch (IOException e) {
                throw new ProvisioningException("Failed to close ModelControllerClient", e);
            }
            mcc = null;
        }
        if(embeddedProcess != null) {
            embeddedProcess.stop();
            embeddedProcess = null;
        }
    }

    void startBatch() throws ProvisioningException {
        if(forkEmbedded) {
            writeScript(BATCH);
        } else {
            composite = Operations.createCompositeOperation();
        }
    }

    void endBatch() throws ProvisioningException {
        if(forkEmbedded) {
            writeScript(RUN_BATCH);
        } else {
            doHandle(composite);
            composite = null;
        }
    }

    void handle(ModelNode op) throws ProvisioningException {
        if(forkEmbedded) {
            op.writeJSONString(scriptWriter, true);
            scriptWriter.println();
        } else if(composite != null) {
            composite.get(WfConstants.STEPS).add(op);
        } else {
            doHandle(op);
        }
    }

    private void doHandle(ModelNode op) throws ProvisioningException {
        try {
            final ModelNode response = mcc.execute(op);
            if(Operations.isSuccessfulOutcome(response)) {
                return;
            }

            final StringBuilder buf = new StringBuilder();
            buf.append("Failed to");
            if(hc) {
                String domainConfig = null;
                boolean emptyDomain = false;
                String hostConfig = null;
                boolean emptyHost = false;
                int i = 0;
                while(i < args.length) {
                    final String arg = args[i++];
                    if(arg.startsWith(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG)) {
                        if(arg.length() == WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG.length()) {
                            domainConfig = args[i++];
                        } else {
                            domainConfig = arg.substring(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG.length() + 1);
                        }
                    } else if(arg.startsWith(WfConstants.EMBEDDED_ARG_HOST_CONFIG)) {
                        if(arg.length() == WfConstants.EMBEDDED_ARG_HOST_CONFIG.length()) {
                            hostConfig = args[i++];
                        } else {
                            hostConfig = arg.substring(WfConstants.EMBEDDED_ARG_HOST_CONFIG.length() + 1);
                        }
                    } else if(arg.equals(WfConstants.EMBEDDED_ARG_EMPTY_HOST_CONFIG)) {
                        emptyHost = true;
                    } else if(arg.equals(WfConstants.EMBEDDED_ARG_EMPTY_DOMAIN_CONFIG)) {
                        emptyDomain = true;
                    }
                }
                if(emptyDomain) {
                    buf.append(" generate ").append(domainConfig);
                    if(emptyHost && hostConfig != null) {
                        buf.append(" and ").append(hostConfig);
                    }
                } else if(emptyHost) {
                    buf.append(" generate ").append(hostConfig);
                } else {
                    buf.append(" execute script");
                }
            } else {
                String serverConfig = null;
                boolean emptyConfig = false;
                int i = 0;
                while(i < args.length) {
                    final String arg = args[i++];
                    if(arg.equals(WfConstants.EMBEDDED_ARG_SERVER_CONFIG)) {
                        if(arg.length() == WfConstants.EMBEDDED_ARG_SERVER_CONFIG.length()) {
                            serverConfig = args[i++];
                        } else {
                            serverConfig = arg.substring(WfConstants.EMBEDDED_ARG_SERVER_CONFIG.length() + 1);
                        }
                    } else if(arg.equals(WfConstants.EMBEDDED_ARG_INTERNAL_EMPTY_CONFIG)) {
                        emptyConfig = true;
                    }
                }
                if(emptyConfig) {
                    buf.append(" generate ").append(serverConfig);
                } else {
                    buf.append(" execute script");
                }
            }
            buf.append(" on ").append(op).append(": ").append(Operations.getFailureDescription(response));
            throw new ProvisioningException(buf.toString());
        } catch (IOException e) {
            throw new ProvisioningException("Failed to execute " + op);
        }
    }

    private void waitForServer() throws ProvisioningException {
        if (bootTimeout == null || bootTimeout > 0) {
            // Poll for server state. Alternative would be to get ControlledProcessStateService
            // and do reflection stuff to read the state and register for change notifications
            long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;
            String status = "starting";
            final ModelNode getStateOp = new ModelNode();
            getStateOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            getStateOp.get(ClientConstants.NAME).set("server-state");
            do {
                try {
                    final ModelNode response = mcc.execute(getStateOp);
                    if (Operations.isSuccessfulOutcome(response)) {
                        status = response.get(ClientConstants.RESULT).asString();
                    }
                } catch (Exception e) {
                    // ignore and try again
                }

                if ("starting".equals(status)) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ProvisioningException("Interrupted while waiting for embedded server to start");
                    }
                } else {
                    break;
                }
            } while (System.nanoTime() < expired);

            if ("starting".equals(status)) {
                assert bootTimeout != null; // we'll assume the loop didn't run for decades
                // Stop server and restore environment
                stopEmbedded();
                throw new ProvisioningException("Embedded server did not exit 'starting' status within " +
                        TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
            }
        }
    }

    private void waitForHc() throws ProvisioningException {
        if (bootTimeout == null || bootTimeout > 0) {
            long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;

            String status = "starting";

            // read out the host controller name
            final ModelNode getNameOp = new ModelNode();
            getNameOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            getNameOp.get(ClientConstants.NAME).set("local-host-name");

            final ModelNode getStateOp = new ModelNode();
            getStateOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            ModelNode address = getStateOp.get(ClientConstants.ADDRESS);
            getStateOp.get(ClientConstants.NAME).set(ClientConstants.HOST_STATE);
            do {
                try {
                    final ModelNode nameResponse = mcc.execute(getNameOp);
                    if (Operations.isSuccessfulOutcome(nameResponse)) {
                        // read out the connected HC name
                        final String localName = nameResponse.get(ClientConstants.RESULT).asString();
                        address.set(ClientConstants.HOST, localName);
                        final ModelNode stateResponse = mcc.execute(getStateOp);
                        if (Operations.isSuccessfulOutcome(stateResponse)) {
                            status = stateResponse.get(ClientConstants.RESULT).asString();
                        }
                    }
                } catch (Exception e) {
                    // ignore and try again
                }

                if ("starting".equals(status)) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ProvisioningException("Interrupted while waiting for embedded server to start");
                    }
                } else {
                    break;
                }
            } while (System.nanoTime() < expired);

            if ("starting".equals(status)) {
                assert bootTimeout != null; // we'll assume the loop didn't run for decades
                // Stop server and restore environment
                stopEmbedded();
                throw new ProvisioningException("Embedded host controller did not exit 'starting' status within " +
                        TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
            }
        }
    }

    private void writeScript(String line) throws ProvisioningException {
        scriptWriter.println(line);
    }

    private void forkEmbedded(MessageWriter messageWriter) throws ProvisioningException {
        final StringBuilder cp = new StringBuilder();
        collectCpUrls(System.getProperty("java.home"), Thread.currentThread().getContextClassLoader(), cp);

        final String[] commands = new String[] {"java",
                "-cp", cp.toString(),
                WfConfigGenerator.class.getName(),
                jbossHome,
                script.toString()};
        Process p;
        try {
            p = new ProcessBuilder(Arrays.asList(commands)).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to start a feature spec reading process", e);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = reader.readLine();
            while (line != null) {
                messageWriter.verbose(line);
                line = reader.readLine();
            }
            if (p.isAlive()) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (p.exitValue() != 0) {
                //System.err.println(writer.getBuffer().toString());
                throw new RuntimeException("Process has failed");
            }
        } catch (IOException e) {
            throw new ProvisioningException("Process has failed", e);
        }
    }

    private static void collectCpUrls(String javaHome, ClassLoader cl, StringBuilder buf) {
        final ClassLoader parentCl = cl.getParent();
        if(parentCl != null) {
            collectCpUrls(javaHome, cl.getParent(), buf);
        }
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader)cl).getURLs()) {
                final String file = url.getFile();
                if(file.startsWith(javaHome)) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(file);
            }
        }
    }

    public static void main(String... args) throws Exception {
        if(args.length != 2) {
            throw new IllegalArgumentException("Expected one argument but received " + Arrays.asList(args));
        }
        final Path script = Paths.get(args[1]);
        if(!Files.exists(script)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(script));
        }
        try {
            executeScript(args[0], script);
        } catch(IOException e) {
            throw new ProvisioningException("Failed to execute configuration script", e);
        }
    }

    private static void executeScript(String jbossHome, Path script) throws IOException, ProvisioningException {
        final WfConfigGenerator configGen = new WfConfigGenerator();
        configGen.jbossHome = jbossHome;
        byte state = INITIAL;

        try (BufferedReader reader = Files.newBufferedReader(script)) {
            String line = reader.readLine();
            while(line != null) {
                if(state == EMBEDDED_STARTED) {
                    if(STOP.equals(line)) {
                        configGen.doStopEmbedded();
                        state = INITIAL;
                    } else if(BATCH.equals(line)) {
                        configGen.startBatch();
                    } else if(RUN_BATCH.equals(line)) {
                        configGen.endBatch();
                    } else {
                        try {
                            configGen.handle(ModelNode.fromJSONString(line));
                        } catch(RuntimeException t) {
                            System.out.println("Failed to parse '" + line + "'");
                            throw t;
                        }
                    }
                } else if((state & LOOKING_FOR_ARGS) > 0) {
                    final String[] args = line.split(",");
                    if((state & START_STANDALONE) > 0) {
                        configGen.doStartServer(args);
                    } else if((state & START_HC) > 0) {
                        configGen.doStartHc(args);
                    } else {
                        throw new IllegalStateException("Unexpected state " + state);
                    }
                    state = EMBEDDED_STARTED;
                } else {
                    if(WfConstants.STANDALONE.equals(line)) {
                        state = START_STANDALONE;
                    } else if(WfConstants.HOST.equals(line)) {
                        state = START_HC;
                    } else {
                        throw new ProvisioningException("Unexpected controller type " + line);
                    }
                    state |= LOOKING_FOR_ARGS;
                }
                line = reader.readLine();
            }
        }
    }
}
