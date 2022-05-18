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

package org.wildfly.galleon.plugin.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

public class ForkedProcessRunner {

   public static void main(String... args) {
      try{
         if(args.length < 2) {
            throw new IllegalStateException("Expected at least two arguments but got " + Arrays.asList(args));
         }

         // set system properties
         setSystemProps(args[0]);

         Class<?> cls;
         try {
            cls = Thread.currentThread().getContextClassLoader().loadClass(args[1]);
         } catch (ClassNotFoundException e) {
            throw new ConfigGeneratorException("Failed to locate the target class " + args[1], e);
         }
        /*if(!ForkCallback.class.isAssignableFrom(cls)) {
            throw new ProvisioningException(args[1] + " does not implement " + ForkCallback.class.getName());
        }*/

         Object o;
         try {
            o = cls.newInstance();
         } catch (Exception e) {
            throw new ConfigGeneratorException("Failed to instantiate " + args[1], e);
         }
         ((ForkCallback)o).forkedForEmbedded(args.length == 2 ? new String[0] : Arrays.copyOfRange(args, 2, args.length));

      } catch (Throwable t) {
         System.err.println("Forked embedded process has failed with the following error:");
         final StringBuilder buf = new StringBuilder();
         while(t != null) {
            buf.setLength(0);
            buf.append(t.getClass().getName());
            if(t.getMessage() != null) {
               buf.append(": ").append(t.getMessage());
            }
            System.err.println(buf.toString());
            for(StackTraceElement e : t.getStackTrace()) {
               buf.setLength(0);
               buf.append("\tat ").append(e.toString());
               System.err.println(buf.toString());
            }
            t = t.getCause();
         }
         System.exit(1);
      }
   }

   private static void setSystemProps(String path) throws ConfigGeneratorException {
      final Path props = Paths.get(path);
      if(!Files.exists(props)) {
         throw new ConfigGeneratorException("Failed to locate " + props.toAbsolutePath());
      }
      final Properties tmp = new Properties();
      try(BufferedReader reader = Files.newBufferedReader(props)) {
         tmp.load(reader);
      } catch (IOException e) {
         throw new ConfigGeneratorException( "Failed to read " + props.toAbsolutePath());
      }
      for(Map.Entry<?, ?> prop : tmp.entrySet()) {
         final String current = System.getProperty(prop.getKey().toString());
         if(current != null) {
            // do not override the default properties
            continue;
         }
         System.setProperty(prop.getKey().toString(), prop.getValue().toString());
      }
   }

}
