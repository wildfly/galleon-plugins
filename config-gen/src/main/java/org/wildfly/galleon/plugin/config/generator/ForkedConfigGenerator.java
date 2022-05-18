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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.jboss.dmr.ModelNode;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ForkCallback;
import org.wildfly.galleon.plugin.server.ConfigGeneratorException;

public class ForkedConfigGenerator extends BaseConfigGenerator implements ForkCallback {

   @Override
   public void forkedForEmbedded(String... args) throws ConfigGeneratorException {
      if(args.length != 2) {
         throw new IllegalArgumentException("Expected one argument but received " + Arrays.asList(args));
      }
      this.jbossHome = args[0];
      final Path script = Paths.get(args[1]);
      if(!Files.exists(script)) {
         throw new ConfigGeneratorException("Failed to locate " + script.toAbsolutePath());
      }
      try {
         executeScript(script);
      } catch(IOException e) {
         throw new ConfigGeneratorException("Failed to execute configuration script", e);
      }
   }

   private void executeScript(Path script) throws IOException, ConfigGeneratorException {
      byte state = INITIAL;
      try (BufferedReader reader = Files.newBufferedReader(script)) {
         String line = reader.readLine();
         while(line != null) {
            if(state == EMBEDDED_STARTED) {
               if(STOP.equals(line)) {
                  doStopEmbedded();
                  state = INITIAL;
               } else if(BATCH.equals(line)) {
                  startBatch();
               } else if(RUN_BATCH.equals(line)) {
                  endBatch();
               } else {
                  try {
                     handle(ModelNode.fromJSONString(line));
                  } catch(RuntimeException t) {
                     System.out.println("Failed to parse '" + line + "'");
                     throw t;
                  }
               }
            } else if((state & LOOKING_FOR_ARGS) > 0) {
               final String[] args = line.split(",");
               if((state & START_STANDALONE) > 0) {
                  doStartServer(args);
               } else if((state & START_HC) > 0) {
                  doStartHc(args);
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
                  throw new ConfigGeneratorException("Unexpected controller type " + line);
               }
               state |= LOOKING_FOR_ARGS;
            }
            line = reader.readLine();
         }
      }
   }
}
