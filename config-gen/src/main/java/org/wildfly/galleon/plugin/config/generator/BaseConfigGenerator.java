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

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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

   protected Object embeddedProcess;
   protected Object mcc;

   protected Object composite;

   protected String jbossHome;
   protected boolean forkEmbedded;
   protected String resetSysProps;
   protected String stabilityLevel;

   protected Path script;
   protected PrintWriter scriptWriter;
   protected StringBuilder scriptBuf;

   private static ServerBridge serverBridge;

   public static void initializeEmbedded(ClassLoader loader) throws ConfigGeneratorException {
        serverBridge = ServerBridge.get(loader);
    }

   protected void handle(String json) throws ConfigGeneratorException {
      if (forkEmbedded) {
          scriptWriter.write(json);
         scriptWriter.println();
      } else if (composite != null) {
          serverBridge.dmr_steps_add(composite, json);
      } else {
         doHandle(json);
      }
   }

   protected void doHandle(String json) throws ConfigGeneratorException {
       Object op = serverBridge.dmr_fromJSON(json);
       doHandle(op);
   }

    private void doHandle(Object op) throws ConfigGeneratorException {
        Object response = serverBridge.mcc_execute(mcc, op);
        if (serverBridge.dmr_isSuccessful(response)) {
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
        buf.append(" on ").append(op).append(": ").append(serverBridge.dmr_getFailureDescription(response));
        throw new ConfigGeneratorException(buf.toString());
    }

   void startBatch() {
      if (forkEmbedded) {
         writeScript(BATCH);
      } else {
          try {
              composite = serverBridge.dmr_createCompositeOperation();
          } catch (Exception ex) {
              throw new RuntimeException(ex);
          }
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
      embeddedProcess = serverBridge.embed_createHostController(jbossHome, args);
      serverBridge.embed_start(embeddedProcess);
      mcc = serverBridge.embed_getModelControllerClient(embeddedProcess);
      waitForHc(embeddedProcess);
   }

   protected void waitForHc(Object embeddedProcess) throws ConfigGeneratorException {
      if (bootTimeout == null || bootTimeout > 0) {
         long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;

         String status;
         do {
            status = serverBridge.embed_getProcessState(embeddedProcess);
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
      try {
          embeddedProcess = serverBridge.embed_createStandalone(jbossHome, args);
          serverBridge.embed_start(embeddedProcess);
          mcc = serverBridge.embed_getModelControllerClient(embeddedProcess);
      } catch (Exception e) {
          throw new ConfigGeneratorException("Failed to start embedded server", e);
      }
      waitForServer();
   }

   protected void doStopEmbedded() throws ConfigGeneratorException {
      //System.out.println("stop embedded");
      if(mcc != null) {
         try {
            serverBridge.mcc_close(mcc);
         } catch (Exception e) {
            throw new ConfigGeneratorException("Failed to close ModelControllerClient", e);
         }
         mcc = null;
      }
      if(embeddedProcess != null) {
          try {
              serverBridge.embed_stop(embeddedProcess);
          } catch (Exception e) {
              throw new ConfigGeneratorException("Failed to close ModelControllerClient", e);
          }
         embeddedProcess = null;
      }
   }

   protected void waitForServer() throws ConfigGeneratorException {
      if (bootTimeout == null || bootTimeout > 0) {
         // Poll for server state. Alternative would be to get ControlledProcessStateService
         // and do reflection stuff to read the state and register for change notifications
         long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;
         String status = "starting";
         final Object getStateOp;
          try {
              getStateOp = serverBridge.dmr_newInstance();
              Object op = serverBridge.dmr_get(getStateOp, serverBridge.OP_FIELD_VALUE);
              serverBridge.dmr_set(op, serverBridge.READ_ATTRIBUTE_OPERATION_FIELD_VALUE);
              Object name = serverBridge.dmr_get(getStateOp, serverBridge.NAME_FIELD_VALUE);
              serverBridge.dmr_set(name, "server-state");
          } catch (Exception ex) {
              throw new ConfigGeneratorException(ex);
          }
         do {
            try {
               final Object response = serverBridge.mcc_execute(mcc, getStateOp);
               if (serverBridge.dmr_isSuccessful(response)) {
                   Object result = serverBridge.dmr_get(response, serverBridge.RESULT_FIELD_VALUE);
                   status = serverBridge.dmr_asString(result);
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
