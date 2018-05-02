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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
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

    private Long bootTimeout = null;

    private String jbossHome;
    private EmbeddedManagedProcess embeddedProcess;
    private ModelControllerClient mcc;

    private boolean hc;
    private String[] args;

    public void generate(ProvisioningRuntime runtime) throws ProvisioningException {

        this.jbossHome = runtime.getStagedDir().toString();
        final MessageWriter messageWriter = runtime.getMessageWriter();
        final WfProvisionedConfigHandler configHandler = new WfProvisionedConfigHandler(runtime, this);
        final Map<?, ?> originalProps = new HashMap<>(System.getProperties());

        try {
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
        } finally {
            try {
                if (embeddedProcess != null) {
                    stopEmbedded();
                }
            } finally {
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
    }

    void startServer(String... args) throws ProvisioningException {
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

    void execute(ModelNode op) throws ProvisioningException {
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
                    if(emptyHost) {
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
}
