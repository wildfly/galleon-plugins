/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ConfigGeneratorException;

public abstract class BaseConfigGenerator {

   protected static final byte INITIAL = 0;
   protected static final byte START_STANDALONE = 1;
   protected static final byte START_HC = 2;
   protected static final byte LOOKING_FOR_ARGS = 4;
   protected static final byte EMBEDDED_STARTED = 8;

   protected static final String BATCH = "batch";
   protected static final String STOP = "stop";
   protected static final String RUN_BATCH = "run-batch";

   protected boolean hc;
   protected String[] args;

   protected Long bootTimeout = null;

   protected EmbeddedManagedProcess embeddedProcess;
   protected ModelControllerClient mcc;

   protected ModelNode composite;

   protected String jbossHome;
   protected boolean forkEmbedded;
   protected String resetSysProps;
   protected String stabilityLevel;

   protected Path script;
   protected PrintWriter scriptWriter;
   protected StringBuilder scriptBuf;

   protected void handle(ModelNode op) throws ConfigGeneratorException {
      if (forkEmbedded) {
         op.writeJSONString(scriptWriter, true);
         scriptWriter.println();
      } else if (composite != null) {
         composite.get(WfConstants.STEPS).add(op);
      } else {
         doHandle(op);
      }
   }

   protected void doHandle(ModelNode op) throws ConfigGeneratorException {
      try {
         final ModelNode response = mcc.execute(op);
         if (Operations.isSuccessfulOutcome(response)) {
            return;
         }

         final StringBuilder buf = new StringBuilder();
         buf.append("Failed to");
         if (hc) {
            String domainConfig = null;
            boolean emptyDomain = false;
            String hostConfig = null;
            boolean emptyHost = false;
            int i = 0;
            while (i < args.length) {
               final String arg = args[i++];
               if (arg.startsWith(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG)) {
                  if (arg.length() == WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG.length()) {
                     domainConfig = args[i++];
                  } else {
                     domainConfig = arg.substring(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG.length() + 1);
                  }
               } else if (arg.startsWith(WfConstants.EMBEDDED_ARG_HOST_CONFIG)) {
                  if (arg.length() == WfConstants.EMBEDDED_ARG_HOST_CONFIG.length()) {
                     hostConfig = args[i++];
                  } else {
                     hostConfig = arg.substring(WfConstants.EMBEDDED_ARG_HOST_CONFIG.length() + 1);
                  }
               } else if (arg.equals(WfConstants.EMBEDDED_ARG_EMPTY_HOST_CONFIG)) {
                  emptyHost = true;
               } else if (arg.equals(WfConstants.EMBEDDED_ARG_EMPTY_DOMAIN_CONFIG)) {
                  emptyDomain = true;
               }
            }
            if (emptyDomain) {
               buf.append(" generate ").append(domainConfig);
               if (emptyHost && hostConfig != null) {
                  buf.append(" and ").append(hostConfig);
               }
            } else if (emptyHost) {
               buf.append(" generate ").append(hostConfig);
            } else {
               buf.append(" execute script");
            }
         } else {
            String serverConfig = null;
            boolean emptyConfig = false;
            int i = 0;
            while (i < args.length) {
               final String arg = args[i++];
               if (arg.equals(WfConstants.EMBEDDED_ARG_SERVER_CONFIG)) {
                  if (arg.length() == WfConstants.EMBEDDED_ARG_SERVER_CONFIG.length()) {
                     serverConfig = args[i++];
                  } else {
                     serverConfig = arg.substring(WfConstants.EMBEDDED_ARG_SERVER_CONFIG.length() + 1);
                  }
               } else if (arg.equals(WfConstants.EMBEDDED_ARG_INTERNAL_EMPTY_CONFIG)) {
                  emptyConfig = true;
               }
            }
            if (emptyConfig) {
               buf.append(" generate ").append(serverConfig);
            } else {
               buf.append(" execute script");
            }
         }
         buf.append(" on ").append(op).append(": ").append(Operations.getFailureDescription(response));
         throw new ConfigGeneratorException(buf.toString());
      } catch (IOException e) {
         throw new ConfigGeneratorException("Failed to execute " + op);
      }
   }

   void startBatch() {
      if (forkEmbedded) {
         writeScript(BATCH);
      } else {
         composite = Operations.createCompositeOperation();
      }
   }

   void endBatch() throws ConfigGeneratorException {
      if (forkEmbedded) {
         writeScript(RUN_BATCH);
      } else {
         doHandle(composite);
         composite = null;
      }
   }

   protected void doStartHc(String... args) throws ConfigGeneratorException {
      //System.out.println("embed hc " + jbossHome + " " + Arrays.asList(args));
      this.args = args;
      this.hc = true;
      embeddedProcess = EmbeddedProcessFactory.createHostController(jbossHome, null, null, args);
      try {
         embeddedProcess.start();
      } catch (EmbeddedProcessStartException e) {
         throw new ConfigGeneratorException("Failed to start embedded hc", e);
      }
      mcc = embeddedProcess.getModelControllerClient();
      waitForHc(embeddedProcess);
   }

   protected void waitForHc(EmbeddedManagedProcess embeddedProcess) throws ConfigGeneratorException {
      if (bootTimeout == null || bootTimeout > 0) {
         long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;

         String status;
         do {
            status = embeddedProcess.getProcessState();
            if (status == null || "starting".equals(status)) {
               try {
                  Thread.sleep(50);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new ConfigGeneratorException("Interrupted while waiting for embedded server to start");
               }
            } else {
               break;
            }
         } while (System.nanoTime() < expired);

         if (status == null || "starting".equals(status)) {
            assert bootTimeout != null; // we'll assume the loop didn't run for decades
            // Stop server and restore environment
            stopEmbedded();
            throw new ConfigGeneratorException("Embedded host controller did not exit 'starting' status within " +
                                                     TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
         }
      }
   }

   protected void doStartServer(String... args) throws ConfigGeneratorException {
      //System.out.println("embed server " + jbossHome + " " + Arrays.asList(args));
      this.args = args;
      this.hc = false;
      embeddedProcess = EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, args);
      try {
         embeddedProcess.start();
      } catch (EmbeddedProcessStartException e) {
         throw new ConfigGeneratorException("Failed to start embedded server", e);
      }
      mcc = embeddedProcess.getModelControllerClient();
      waitForServer();
   }

   protected void doStopEmbedded() throws ConfigGeneratorException {
      //System.out.println("stop embedded");
      if(mcc != null) {
         try {
            mcc.close();
         } catch (IOException e) {
            throw new ConfigGeneratorException("Failed to close ModelControllerClient", e);
         }
         mcc = null;
      }
      if(embeddedProcess != null) {
         embeddedProcess.stop();
         embeddedProcess = null;
      }
   }

   protected void waitForServer() throws ConfigGeneratorException {
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
                  throw new ConfigGeneratorException("Interrupted while waiting for embedded server to start");
               }
            } else {
               break;
            }
         } while (System.nanoTime() < expired);

         if ("starting".equals(status)) {
            assert bootTimeout != null; // we'll assume the loop didn't run for decades
            // Stop server and restore environment
            stopEmbedded();
            throw new ConfigGeneratorException("Embedded server did not exit 'starting' status within " +
                                                     TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
         }
      }
   }

   void stopEmbedded() throws ConfigGeneratorException {
      if (forkEmbedded) {
         writeScript(STOP);
      } else {
         doStopEmbedded();
      }
   }

   protected void writeScript(String line) {
      scriptWriter.println(line);
   }
}
